package mill.bun

import mill.testkit.IntegrationTester
import utest._

object BunTypeScriptIntegrationTests extends TestSuite {
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  def tests: Tests = Tests {

    test("compile") {
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "typescript-simple",
        millExecutable = millExe
      )

      val res = tester.eval("app.compile")
      assert(res.isSuccess)
    }

    test("bundle") {
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "typescript-bundle",
        millExecutable = millExe
      )

      val res = tester.eval("app.bundle")
      assert(res.isSuccess)
    }

    test("compile-executable") {
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "typescript-compile",
        millExecutable = millExe
      )

      val res = tester.eval("app.bundle")
      assert(res.isSuccess)
    }
  }
}
