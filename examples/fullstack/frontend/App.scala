import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalajs.dom
import org.scalajs.dom.document
import org.scalajs.dom.html

object App {
  def main(args: Array[String]): Unit = {
    val nameInput = document.getElementById("name-input").asInstanceOf[html.Input]
    val greetBtn = document.getElementById("greet-btn")
    val result = document.getElementById("result")
    val counterBtn = document.getElementById("counter-btn")
    val counterDisplay = document.getElementById("counter")

    greetBtn.addEventListener("click", { (_: dom.Event) =>
      val name = nameInput.value.trim
      if (name.nonEmpty) fetchGreeting(name, result)
    })

    nameInput.addEventListener("keypress", { (e: dom.KeyboardEvent) =>
      if (e.key == "Enter") {
        val name = nameInput.value.trim
        if (name.nonEmpty) fetchGreeting(name, result)
      }
    })

    counterBtn.addEventListener("click", { (_: dom.Event) =>
      fetchCounter(counterDisplay)
    })
  }

  private def fetchGreeting(name: String, target: dom.Element): Unit = {
    val encoded = js.URIUtils.encodeURIComponent(name)
    for {
      response <- dom.fetch(s"/api/greet/$encoded").toFuture
      text     <- response.text().toFuture
    } yield {
      val greeting = js.JSON.parse(text).asInstanceOf[Greeting]
      target.textContent = greeting.message
    }
  }

  private def fetchCounter(target: dom.Element): Unit = {
    for {
      response <- dom.fetch("/api/counter").toFuture
      text     <- response.text().toFuture
    } yield {
      val counter = js.JSON.parse(text).asInstanceOf[Counter]
      target.textContent = s"Visits: ${counter.count}"
    }
  }
}
