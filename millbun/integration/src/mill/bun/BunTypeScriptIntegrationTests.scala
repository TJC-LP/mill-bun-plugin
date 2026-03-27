package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest._

object BunTypeScriptIntegrationTests extends TestSuite {
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

    test("compile") {
      val tester = this.tester("typescript-simple")
      val res = tester.eval("app.compile")
      assert(res.isSuccess)
    }

    test("bundle") {
      val tester = this.tester("typescript-bundle")
      val res = tester.eval("app.bundle")
      assert(res.isSuccess)

      val bundle = outputPath(tester, "app.bundle")
      assert(os.exists(bundle / os.up / "resources" / "nested" / "config.json"))

      val run = os.call(
        Seq("bun", bundle.toString),
        cwd = bundle / os.up
      )
      assert(run.out.text().trim == "Hello from bundled TypeScript resources!")
    }

    test("compile-executable") {
      val tester = this.tester("typescript-compile")
      val res = tester.eval("app.bundle")
      assert(res.isSuccess)

      val executable = outputPath(tester, "app.bundle")
      val run = os.call(
        Seq(executable.toString),
        cwd = executable / os.up
      )
      assert(run.out.text().trim == "Hello from compiled TypeScript executable!")
    }

    test("bunEnv") {
      val tester = this.tester("typescript-env")
      val res = tester.eval("app.bundle")
      assert(res.isSuccess)

      val installLog = os.read(tester.workspacePath / "out" / "app" / "npmInstall.dest" / ".bun-env-log")
      val compileLog = os.read(tester.workspacePath / "out" / "app" / "compile.dest" / ".bun-env-log")
      val bundleLog = os.read(tester.workspacePath / "out" / "app" / "bundle.dest" / ".bun-env-log")

      assert(installLog.contains("install:present"))
      assert(compileLog.contains("x:present"))
      assert(bundleLog.contains("build:present"))
    }
  }
}
