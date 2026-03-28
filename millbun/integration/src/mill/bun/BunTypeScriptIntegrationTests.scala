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

  private def commandLogPath(tester: IntegrationTester, selector: String): os.Path = {
    val segments = selector.split('.')
    val rel =
      if (segments.length <= 1) os.RelPath(".")
      else os.RelPath(segments.dropRight(1).mkString("/"))
    tester.workspacePath / "out" / rel / s"${segments.last}.log"
  }

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

    test("run") {
      val tester = this.tester("typescript-simple")
      val res = tester.eval("app.run")
      assert(res.isSuccess)

      val log = os.read(commandLogPath(tester, "app.run")).trim
      assert(log == "Hello from TypeScript on Bun!")
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

    test("bun target ambient types are pinned") {
      val tester = this.tester("typescript-simple")
      val res = tester.eval("app.npmInstall")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "app" / "npmInstall.dest" / "package.json"))
      val devDeps = packageJson("devDependencies").obj

      assert(devDeps("@types/bun").str == "1.3.11")
      assert(devDeps("@types/bun").str != "latest")
      assert(!devDeps.contains("@types/node"))
    }

    test("browser target does not install node or bun ambient types") {
      val tester = this.tester("typescript-browser")
      val res = tester.eval("app.compile")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "app" / "npmInstall.dest" / "package.json"))
      val devDeps = packageJson("devDependencies").obj

      assert(devDeps("typescript").str == "5.7.3")
      assert(!devDeps.contains("@types/node"))
      assert(!devDeps.contains("@types/bun"))
    }

    test("bun test module") {
      val tester = this.tester("typescript-tests")
      val res = tester.eval("app.test.test")
      assert(res.isSuccess)
    }

    test("bundle workers") {
      val tester = this.tester("typescript-workers")
      val res = tester.eval("app.bundleWorkers")
      assert(res.isSuccess)

      val workersDir = outputPath(tester, "app.bundleWorkers")
      val alphaWorker = workersDir / "src" / "workers" / "alpha" / "worker.js"
      val betaWorker = workersDir / "src" / "workers" / "beta" / "worker.js"

      assert(os.exists(alphaWorker))
      assert(os.exists(betaWorker))

      val alphaRun = os.call(Seq("bun", alphaWorker.last), cwd = alphaWorker / os.up)
      val betaRun = os.call(Seq("bun", betaWorker.last), cwd = betaWorker / os.up)

      assert(alphaRun.out.text().trim == "Alpha worker")
      assert(betaRun.out.text().trim == "Beta worker")
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
