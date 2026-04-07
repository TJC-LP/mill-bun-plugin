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

    // Invalid literal coverage lives in integration tests so the interpolator
    // is compiled in a normal build.mill context rather than inside another macro.
  }
}
