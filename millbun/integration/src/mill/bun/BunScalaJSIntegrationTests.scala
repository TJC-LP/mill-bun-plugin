package mill.bun

import mill.testkit.IntegrationTester
import utest._

object BunScalaJSIntegrationTests extends TestSuite {
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  def tests: Tests = Tests {

    test("fastLinkJS") {
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "scalajs-simple",
        millExecutable = millExe
      )

      val res = tester.eval("app.fastLinkJS")
      assert(res.isSuccess)
    }

    test("bunInstall") {
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "scalajs-bundle",
        millExecutable = millExe
      )

      val res = tester.eval("app.bunInstall")
      assert(res.isSuccess)
    }

    test("bunBundle") {
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "scalajs-bundle",
        millExecutable = millExe
      )

      val res = tester.eval("app.bunBundle")
      assert(res.isSuccess)
    }
  }
}
