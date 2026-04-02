package mill.bun

import mill.api.PathRef
import mill.testkit.IntegrationTester
import utest._

object BunScalaJSIntegrationTests extends TestSuite {
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

  private def bundledScript(dist: os.Path): os.Path =
    os.walk(dist)
      .find(path => os.isFile(path) && path.ext == "js")
      .getOrElse(throw new java.lang.AssertionError(s"No bundled JavaScript file found in $dist"))

  private def runBundledScript(script: os.Path): String =
    os.call(Seq("bun", script.last), cwd = script / os.up).out.text().trim

  private def runExecutable(executable: os.Path): String =
    os.call(Seq(executable.toString), cwd = executable / os.up).out.text().trim

  def tests: Tests = Tests {

    test("fastLinkJS") {
      val tester = this.tester("scalajs-simple")
      val res = tester.eval("app.fastLinkJS")
      assert(res.isSuccess)
    }

    test("run") {
      val tester = this.tester("scalajs-bundle")
      val res = tester.eval("app.run")
      assert(res.isSuccess)

      val log = os.read(commandLogPath(tester, "app.run")).trim
      assert(log == "Hello from scala.js with lodash on bun")
    }

    test("bunInstall") {
      val tester = this.tester("scalajs-bundle")
      val res = tester.eval("app.bunInstall")
      assert(res.isSuccess)
    }

    test("bunBundle") {
      val tester = this.tester("scalajs-bundle")
      val res = tester.eval("app.bunBundle")
      assert(res.isSuccess)

      val dist = outputPath(tester, "app.bunBundle")
      val mainJs = bundledScript(dist)
      assert(runBundledScript(mainJs) == "Hello from scala.js with lodash on bun")
    }

    test("bunCompileExecutable") {
      val tester = this.tester("scalajs-bundle")
      val res = tester.eval("app.bunCompileExecutable")
      assert(res.isSuccess)

      val executable = outputPath(tester, "app.bunCompileExecutable")
      assert(runExecutable(executable) == "Hello from scala.js with lodash on bun")
    }

    test("transitive npm deps") {
      val tester = this.tester("scalajs-transitive")
      val res = tester.eval("app.bunBundle")
      assert(res.isSuccess)

      val dist = outputPath(tester, "app.bunBundle")
      val mainJs = bundledScript(dist)
      assert(runBundledScript(mainJs) == "Hello from transitive scala.js bun")
    }

    test("bunTest") {
      val tester = this.tester("scalajs-test")
      val res = tester.eval("app.test.bunTest")
      assert(res.isSuccess)
    }

    test("bunfig propagates to Scala.js workspaces without leaking .npmrc") {
      val tester = this.tester("scalajs-bunfig")

      val installRes = tester.eval("app.bunInstall")
      assert(installRes.isSuccess)
      val installDir = tester.workspacePath / "out" / "app" / "bunInstall.dest"
      assert(os.exists(installDir / ".npmrc"))
      assert(os.exists(installDir / "bunfig.toml"))

      val linkRes = tester.eval("app.fastLinkJS")
      assert(linkRes.isSuccess)
      val linkedDir = tester.workspacePath / "out" / "app" / "fastLinkJS.dest"
      assert(os.exists(linkedDir / "bunfig.toml"))
      assert(!os.exists(linkedDir / ".npmrc"))

      val compileRes = tester.eval("app.bunCompileExecutable")
      assert(compileRes.isSuccess)
      val compileWorkspace = tester.workspacePath / "out" / "app" / "bunCompileExecutable.dest" / "workspace"
      assert(os.exists(compileWorkspace / "bunfig.toml"))
      assert(!os.exists(compileWorkspace / ".npmrc"))

      val testRes = tester.eval("app.test.bunTest")
      assert(testRes.isSuccess)
      val testRoot = tester.workspacePath / "out" / "app" / "test"
      assert(os.exists(testRoot))
      assert(os.walk(testRoot).exists(_.last == "bunfig.toml"))
      assert(!os.walk(testRoot).exists(_.last == ".npmrc"))
    }
  }
}
