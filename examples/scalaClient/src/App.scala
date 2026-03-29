import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalajs.dom
import org.scalajs.dom.document
import org.scalajs.dom.html

object App {
  def main(args: Array[String]): Unit = {
    val app = document.getElementById("app")
    app.innerHTML = ""

    val heading = document.createElement("h1")
    heading.textContent = "Scala.js Frontend"
    app.appendChild(heading)

    val desc = document.createElement("p")
    desc.textContent = "This Scala.js frontend calls the same Cask JVM backend API."
    app.appendChild(desc)

    // Greeting section
    val section = document.createElement("section")
    val row = document.createElement("div")
    row.classList.add("row")

    val input = document.createElement("input").asInstanceOf[html.Input]
    input.placeholder = "Enter a name"
    val btn = document.createElement("button").asInstanceOf[html.Button]
    btn.textContent = "Greet"
    val result = document.createElement("div")
    result.classList.add("result")

    row.appendChild(input)
    row.appendChild(btn)
    section.appendChild(row)
    section.appendChild(result)
    app.appendChild(section)

    btn.addEventListener("click", { (_: dom.Event) =>
      val name = input.value.trim
      if (name.nonEmpty) {
        val encoded = js.URIUtils.encodeURIComponent(name)
        for {
          response <- dom.fetch(s"/api/greet/$encoded").toFuture
          text <- response.text().toFuture
        } yield {
          val data = js.JSON.parse(text)
          result.textContent = data.selectDynamic("message").asInstanceOf[String]
        }
      }
    })

    // Time section
    val timeSection = document.createElement("section")
    val timeBtn = document.createElement("button").asInstanceOf[html.Button]
    timeBtn.textContent = "Get Server Time"
    val timeResult = document.createElement("div")
    timeResult.classList.add("result")
    timeSection.appendChild(timeBtn)
    timeSection.appendChild(timeResult)
    app.appendChild(timeSection)

    timeBtn.addEventListener("click", { (_: dom.Event) =>
      for {
        response <- dom.fetch("/api/time").toFuture
        text <- response.text().toFuture
      } yield {
        val data = js.JSON.parse(text)
        timeResult.textContent = s"Server time: ${data.selectDynamic("time").asInstanceOf[String]}"
      }
    })
  }
}
