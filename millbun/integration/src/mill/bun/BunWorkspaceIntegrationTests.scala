package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest.*

object BunWorkspaceIntegrationTests extends BunIntegrationSuite:
  def tests: Tests = Tests:
    test("mixed Scala.js and TypeScript packages share one install"):
      val tester = this.tester("mixed-workspace")

      val lockResult = tester.eval("workspace.bunLock")
      assert(lockResult.isSuccess)
      assert(os.exists(tester.workspacePath / "bun.lock"))

      val workspaceResult = tester.eval("workspace.bunInstall")
      assert(workspaceResult.isSuccess)
      val workspaceInstall = outputPath(tester, "workspace.bunInstall")
      assert(os.read(workspaceInstall / ".workspace-installed").contains("--frozen-lockfile"))
      val rootJson = ujson.read(os.read(workspaceInstall / "package.json"))
      assert(rootJson("workspaces").arr.map(_.str).toSet == Set(
        "packages/scala-app-renamed",
        "packages/typescriptApp"
      ))
      assert(os.exists(workspaceInstall / "node_modules" / "is-even" / "package.json"))
      assert(os.exists(workspaceInstall / "node_modules" / "is-odd" / "package.json"))

      // Unmanaged local packages arrive as file: specifiers with vendor trees staged beside the
      // member's package.json — never as positional install args, which turn `bun install` into
      // `bun add` and are unconditionally rejected by --frozen-lockfile.
      val scalaJson = ujson.read(os.read(workspaceInstall / "packages" / "scala-app-renamed" / "package.json"))
      // The manifest identity must be the workspace package name, not the module's default —
      // otherwise a rename satisfies Mill's duplicate guard while bun still sees collisions.
      assert(scalaJson("name").str == "scala-app-renamed")
      assert(scalaJson("dependencies").obj("shared-local").str == "file:./vendor/shared-local")
      assert(os.exists(workspaceInstall / "packages" / "scala-app-renamed" / "vendor" / "shared-local" / "package.json"))
      assert(!os.read(workspaceInstall / ".workspace-installed").contains("shared-local"))

      val scalaResult = tester.eval("scalaApp.bunInstall")
      val typescriptResult = tester.eval("typescriptApp.bunInstall")
      assert(scalaResult.isSuccess)
      assert(typescriptResult.isSuccess)
      assert(os.isLink(outputPath(tester, "scalaApp.bunInstall") / "node_modules"))
      assert(os.isLink(outputPath(tester, "typescriptApp.bunInstall") / "node_modules"))
