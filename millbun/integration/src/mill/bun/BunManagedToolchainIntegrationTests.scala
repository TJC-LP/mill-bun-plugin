package mill.bun

import mill.testkit.IntegrationTester
import utest.*

object BunManagedToolchainIntegrationTests extends TestSuite:
  val resourceDir: os.Path = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "millbun" / "integration" / "resources"
  val millExe: os.Path = os.Path(sys.env("MILL_EXECUTABLE_PATH"))

  def tests: Tests = Tests:
    test("resolved toolchain runs the pinned Bun version"):
      val tester = new IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourceDir / "managed-bun",
        millExecutable = millExe,
        useInMemory = true
      )
      val result = tester.eval("app.bunExecutable")
      assert(result.isSuccess)
      val executable = tester.out("app.bunExecutable").value[String]
      val version = os.proc(executable, "--version").call(stdout = os.Pipe).out.text().trim
      assert(version == "1.3.14")
