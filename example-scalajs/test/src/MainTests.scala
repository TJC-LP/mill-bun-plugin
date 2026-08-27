import utest.*

object MainTests extends TestSuite {
  def tests: Tests = Tests {
    test("lodash resolves under the Bun test runtime") {
      assert(Lodash.capitalize("bun") == "Bun")
    }
  }
}
