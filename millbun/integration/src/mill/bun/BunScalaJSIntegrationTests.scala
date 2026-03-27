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
  }
}
