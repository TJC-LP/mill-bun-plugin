package mill.bun

import utest._

object BunDepTests extends TestSuite {
  def tests: Tests = Tests {

    test("simple package with version") {
      val dep = bun"react@^19.0.0"
      assert(dep == "react@^19.0.0")
    }

    test("scoped package with version") {
      val dep = bun"@anthropic-ai/claude-agent-sdk@^0.2.90"
      assert(dep == "@anthropic-ai/claude-agent-sdk@^0.2.90")
    }

    test("package without version (latest)") {
      val dep = bun"zod"
      assert(dep == "zod")
    }

    test("scoped package without version") {
      val dep = bun"@types/node"
      assert(dep == "@types/node")
    }

    test("package with exact version") {
      val dep = bun"react@19.1.1"
      assert(dep == "react@19.1.1")
    }

    test("package with tilde range") {
      val dep = bun"lodash@~4.17.0"
      assert(dep == "lodash@~4.17.0")
    }

    test("returns plain String type") {
      val dep: String = bun"react@19.0.0"
      assert(dep.isInstanceOf[String])
    }

    test("works in Seq for bunDeps") {
      val deps: Seq[String] = Seq(
        bun"@anthropic-ai/claude-agent-sdk@^0.2.90",
        bun"@openai/codex-sdk@^0.118.0",
        bun"zod@^4.0.0"
      )
      assert(deps.length == 3)
      assert(deps.head.startsWith("@anthropic-ai"))
    }

    test("the interpolator and the task-time parser accept the same inputs") {
      // The macro used to run its own weaker parser, so bun"react@" compiled and then threw
      // during the install. Both must now agree, in both directions.
      Seq("react@^19.0.0", "@types/node", "zod", "lodash@~4.17.0", "@scope/pkg@1.0.0").foreach {
        valid =>
          assert(BunDep.validate(valid) == valid)
          assert(BunToolchainModule.parseDependency(valid).isRight)
      }

      Seq("", "react@", "@types", "@types/bun@", "@/pkg").foreach { invalid =>
        val error = intercept[IllegalArgumentException](BunDep.validate(invalid))
        assert(error.getMessage.contains("Invalid bun dependency"))
        assert(BunToolchainModule.parseDependency(invalid).isLeft)
      }
    }

    test("interpolated forms are validated when the build evaluates them") {
      // Not knowable at compile time, so this is the runtime backstop.
      val version = "^19.0.0"
      assert(bun"react@$version" == "react@^19.0.0")

      val empty = ""
      intercept[IllegalArgumentException](bun"react@$empty")
    }

    // Invalid *literal* coverage lives in integration tests so the interpolator
    // is compiled in a normal build.mill context rather than inside another macro.
  }
}
