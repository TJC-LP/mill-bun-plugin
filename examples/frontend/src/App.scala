import org.scalajs.dom
import org.scalajs.dom.document
import org.scalajs.dom.html

case class Todo(id: Int, text: String, done: Boolean)

object App {
  private var todos = List.empty[Todo]
  private var nextId = 1

  def main(args: Array[String]): Unit = {
    val input = document.getElementById("todo-input").asInstanceOf[html.Input]
    val addBtn = document.getElementById("add-btn")

    addBtn.addEventListener("click", { (_: dom.Event) =>
      addTodo(input)
    })

    input.addEventListener("keypress", { (e: dom.KeyboardEvent) =>
      if (e.key == "Enter") addTodo(input)
    })

    render()
  }

  private def addTodo(input: html.Input): Unit = {
    val text = input.value.trim
    if (text.nonEmpty) {
      todos = todos :+ Todo(nextId, text, done = false)
      nextId += 1
      input.value = ""
      render()
    }
  }

  private def render(): Unit = {
    val list = document.getElementById("todo-list")
    list.innerHTML = ""

    todos.foreach { todo =>
      val li = document.createElement("li").asInstanceOf[html.LI]
      if (todo.done) li.classList.add("done")

      val span = document.createElement("span")
      span.textContent = todo.text
      span.addEventListener("click", { (_: dom.Event) =>
        todos = todos.map(t =>
          if (t.id == todo.id) t.copy(done = !t.done) else t
        )
        render()
      })

      val btn = document.createElement("button").asInstanceOf[html.Button]
      btn.textContent = "\u00d7"
      btn.addEventListener("click", { (_: dom.Event) =>
        todos = todos.filterNot(_.id == todo.id)
        render()
      })

      li.appendChild(span)
      li.appendChild(btn)
      list.appendChild(li)
    }

    val counter = document.getElementById("counter")
    val remaining = todos.count(!_.done)
    counter.textContent = s"$remaining item${if (remaining != 1) "s" else ""} left"
  }
}
