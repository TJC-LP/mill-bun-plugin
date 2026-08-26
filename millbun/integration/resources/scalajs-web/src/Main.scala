import org.scalajs.dom.document
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Forces the linker to emit a real npm import, so the staged web build must resolve it. */
@js.native
@JSImport("lodash", JSImport.Namespace)
object Lodash extends js.Object:
  def capitalize(value: String): String = js.native

object Main:
  def main(args: Array[String]): Unit =
    document.getElementById("app").textContent = Lodash.capitalize("hello from scala.js web")
