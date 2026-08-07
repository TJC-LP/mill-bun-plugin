import org.scalajs.dom.document

object Main:
  def main(args: Array[String]): Unit =
    document.getElementById("app").textContent = "Hello from Scala.js web"
