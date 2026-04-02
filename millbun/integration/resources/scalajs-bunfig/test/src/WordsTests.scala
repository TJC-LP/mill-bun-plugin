import utest.*

object WordsTests extends TestSuite {
  val tests: Tests = Tests {
    test("greeting") {
      assert(Words.greeting("Bun") == "Hello, Bun!")
    }
  }
}
