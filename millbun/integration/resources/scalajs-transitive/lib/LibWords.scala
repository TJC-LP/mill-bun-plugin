import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object LibWords {
  @js.native
  @JSImport("lodash", JSImport.Default)
  private object Lodash extends js.Object {
    def capitalize(s: String): String = js.native
  }

  def greeting(subject: String): String = Lodash.capitalize(s"hello from $subject")
}
