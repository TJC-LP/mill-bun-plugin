package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest.*

object BunWorkspaceIntegrationTests extends TestSuite:
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  private def tester(): IntegrationTester =
    new IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = resourceDir / "mixed-workspace",
      millExecutable = millExe,
      useInMemory = true
    )

  private def outputPath(tester: IntegrationTester, selector: String): os.Path =
    tester.out(selector).value[PathRef].path

  def tests: Tests = Tests:
    test("mixed Scala.js and TypeScript packages share one install"):
      val tester = this.tester()

      val lockResult = tester.eval("workspace.bunLock")
      assert(lockResult.isSuccess)
      assert(os.exists(tester.workspacePath / "bun.lock"))

      val workspaceResult = tester.eval("workspace.bunInstall")
      assert(workspaceResult.isSuccess)
      val workspaceInstall = outputPath(tester, "workspace.bunInstall")
      assert(os.read(workspaceInstall / ".workspace-installed").contains("--frozen-lockfile"))
      val rootJson = ujson.read(os.read(workspaceInstall / "package.json"))
      assert(rootJson("workspaces").arr.map(_.str).toSet == Set(
        "packages/scalaApp",
        "packages/typescriptApp"
      ))
      assert(os.exists(workspaceInstall / "node_modules" / "is-even" / "package.json"))
      assert(os.exists(workspaceInstall / "node_modules" / "is-odd" / "package.json"))

      val scalaResult = tester.eval("scalaApp.bunInstall")
      val typescriptResult = tester.eval("typescriptApp.npmInstall")
      assert(scalaResult.isSuccess)
      assert(typescriptResult.isSuccess)
      assert(os.isLink(outputPath(tester, "scalaApp.bunInstall") / "node_modules"))
      assert(os.isLink(outputPath(tester, "typescriptApp.npmInstall") / "node_modules"))
