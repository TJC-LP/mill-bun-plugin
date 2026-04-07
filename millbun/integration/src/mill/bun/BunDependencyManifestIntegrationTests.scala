package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest.*

object BunDependencyManifestIntegrationTests extends TestSuite {
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  private def tester(resource: String): IntegrationTester =
    new IntegrationTester(
      daemonMode = false,
      workspaceSourcePath = resourceDir / resource,
      millExecutable = millExe,
      useInMemory = true
    )

  private def outputPath(tester: IntegrationTester, selector: String): os.Path =
    tester.out(selector).value[PathRef].path

  def tests: Tests = Tests {

    test("invalid bun literal fails in build definitions") {
      val tester = this.tester("invalid-bun-literal")
      val res = tester.eval("app.bunDeps")
      assert(!res.isSuccess)
    }

    test("published manifests include dev-only modules") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("publishedLib.jar")
      assert(res.isSuccess)

      val jar = outputPath(tester, "publishedLib.jar")
      val manifest = BunManifest.readFromJar(jar)
      assert(manifest.isDefined)
      assert(manifest.get.dependencies.isEmpty)
      assert(manifest.get.devDependencies == Map("dev-only" -> "^2.0.0"))
      assert(manifest.get.optionalDependencies == Map("optional-published" -> "^3.0.0"))
    }

    test("local optional deps flow into generated package.json") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("appLocal.bunInstall")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "appLocal" / "bunInstall.dest" / "package.json"))
      assert(packageJson("optionalDependencies").obj("optional-local").str == "^1.0.0")
    }

    test("classpath manifests flow dev and optional deps into generated package.json") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("appPublished.bunInstall")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "appPublished" / "bunInstall.dest" / "package.json"))
      assert(packageJson("devDependencies").obj("dev-only").str == "^2.0.0")
      assert(packageJson("optionalDependencies").obj("optional-published").str == "^3.0.0")
    }
  }
}
