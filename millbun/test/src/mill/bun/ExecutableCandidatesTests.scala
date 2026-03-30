package mill.bun

import utest._

object ExecutableCandidatesTests extends TestSuite {
  def tests: Tests = Tests {

    test("no PATHEXT returns bare name only") {
      val candidates = BunToolchainModule.executableCandidates("bun", "")
      assert(candidates == Seq("bun"))
    }

    test("PATHEXT adds lowercase extensions after bare name") {
      val candidates = BunToolchainModule.executableCandidates("bun", ".COM;.EXE;.CMD")
      assert(candidates == Seq("bun", "bun.com", "bun.exe", "bun.cmd"))
    }

    test("single PATHEXT entry") {
      val candidates = BunToolchainModule.executableCandidates("bun", ".EXE")
      assert(candidates == Seq("bun", "bun.exe"))
    }

    test("PATHEXT with trailing separator is ignored") {
      val candidates = BunToolchainModule.executableCandidates("bun", ".EXE;")
      assert(candidates == Seq("bun", "bun.exe"))
    }
  }
}
