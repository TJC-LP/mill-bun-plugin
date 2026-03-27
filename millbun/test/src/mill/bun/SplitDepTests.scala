package mill.bun

import utest._

object SplitDepTests extends TestSuite {
  def tests: Tests = Tests {

    test("simple package with version") {
      val (name, version) = BunToolchainModule.splitDep("react@19.1.1")
      assert(name == "react")
      assert(version.str == "19.1.1")
    }

    test("scoped package with version") {
      val (name, version) = BunToolchainModule.splitDep("@types/node@22.10.9")
      assert(name == "@types/node")
      assert(version.str == "22.10.9")
    }

    test("simple package without version") {
      val (name, version) = BunToolchainModule.splitDep("react")
      assert(name == "react")
      assert(version.str == "")
    }

    test("scoped package without version") {
      val (name, version) = BunToolchainModule.splitDep("@types/bun")
      assert(name == "@types/bun")
      assert(version.str == "")
    }

    test("scoped package with latest tag") {
      val (name, version) = BunToolchainModule.splitDep("@types/bun@latest")
      assert(name == "@types/bun")
      assert(version.str == "latest")
    }

    test("package with semver range") {
      val (name, version) = BunToolchainModule.splitDep("typescript@^5.7.3")
      assert(name == "typescript")
      assert(version.str == "^5.7.3")
    }

    test("deeply scoped package") {
      val (name, version) = BunToolchainModule.splitDep("@esbuild-plugins/tsconfig-paths@1.0.0")
      assert(name == "@esbuild-plugins/tsconfig-paths")
      assert(version.str == "1.0.0")
    }
  }
}
