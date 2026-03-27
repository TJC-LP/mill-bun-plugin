import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("lodash", JSImport.Default)
object Lodash extends js.Object {
  def capitalize(s: String): String = js.native
}

object Main {
  def main(args: Array[String]): Unit = {
    println(Lodash.capitalize("hello from scala.js with lodash on bun"))
  }
}
