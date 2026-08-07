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

    test("published dev-only modules do not emit runtime manifests") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("publishedDevOnlyLib.jar")
      assert(res.isSuccess)

      val jar = outputPath(tester, "publishedDevOnlyLib.jar")
      val manifest = BunManifest.readFromJar(jar)
      assert(manifest.isEmpty)
    }

    test("published manifests exclude local development dependencies") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("publishedLib.jar")
      assert(res.isSuccess)

      val jar = outputPath(tester, "publishedLib.jar")
      val manifest = BunManifest.readFromJar(jar)
      assert(manifest.isDefined)
      assert(manifest.get.schemaVersion == 2)
      assert(manifest.get.dependencies.isEmpty)
      assert(manifest.get.devDependencies.isEmpty)
      assert(manifest.get.optionalDependencies == Map("optional-published" -> "^3.0.0"))
      assert(manifest.get.peerDependencies == Map("peer-published" -> "^4.0.0"))
    }

    test("published jars stay manifest-only by default") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("publishedLib.jar")
      assert(res.isSuccess)

      val jar = outputPath(tester, "publishedLib.jar")
      assert(!BunVendoredNodeModules.hasVendoredNodeModules(jar))
    }

    test("local optional deps flow into generated package.json") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("appLocal.bunInstall")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "appLocal" / "bunInstall.dest" / "package.json"))
      assert(packageJson("optionalDependencies").obj("optional-local").str == "^1.0.0")
    }

    test("classpath manifests flow publishable deps into generated package.json") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("appPublished.bunInstall")
      assert(res.isSuccess)

      val packageJson = ujson.read(os.read(tester.workspacePath / "out" / "appPublished" / "bunInstall.dest" / "package.json"))
      assert(!packageJson("devDependencies").obj.contains("dev-only"))
      assert(packageJson("optionalDependencies").obj("optional-published").str == "^3.0.0")
      assert(packageJson("peerDependencies").obj("peer-published").str == "^4.0.0")
    }

    test("bunInstall runs for typed npm dependencies") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("appExtrasOnly.bunInstall")
      assert(res.isSuccess)

      val installDir = tester.workspacePath / "out" / "appExtrasOnly" / "bunInstall.dest"
      assert(os.exists(installDir / ".stub-bun-ran"))
      assert(os.exists(installDir / "node_modules" / "extras-only" / "package.json"))
    }

    test("bunPublishedRuntimeInstall runs for vendored typed published deps") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("publishedVendoredExtraLib.bunPublishedRuntimeInstall")
      assert(res.isSuccess)

      val installDir = tester.workspacePath / "out" / "publishedVendoredExtraLib" / "bunPublishedRuntimeInstall.dest"
      assert(os.exists(installDir / ".stub-bun-ran"))
      assert(os.exists(installDir / "node_modules" / "vendored-extra" / "package.json"))
    }

    test("opted-in published jars embed vendored runtime") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("publishedVendoredExtraLib.jar")
      assert(res.isSuccess)

      val jar = outputPath(tester, "publishedVendoredExtraLib.jar")
      val manifest = BunManifest.readFromJar(jar)
      assert(manifest.isDefined)
      assert(manifest.get.dependencies == Map("vendored-extra" -> "^4.0.0"))
      assert(BunVendoredNodeModules.hasVendoredNodeModules(jar))
    }

    test("consumer bunInstall merges opted-in vendored runtime") {
      val tester = this.tester("scalajs-dependency-manifests")
      val res = tester.eval("appVendored.bunInstall")
      assert(res.isSuccess)

      val installDir = tester.workspacePath / "out" / "appVendored" / "bunInstall.dest"
      assert(os.exists(installDir / "node_modules" / "vendored-extra" / "package.json"))
    }
  }
}
