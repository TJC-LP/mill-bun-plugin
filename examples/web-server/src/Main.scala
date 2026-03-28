import scala.scalajs.js
import scala.scalajs.js.annotation.*

// ---- Minimal Bun / Web API facades ----

@js.native @JSGlobal
class URL(input: String) extends js.Object {
  val pathname: String = js.native
}

@js.native @JSGlobal
class Request extends js.Object {
  val url: String = js.native
  val method: String = js.native
}

@js.native @JSGlobal
class Response(body: String) extends js.Object

@js.native @JSGlobal("Response")
object Response extends js.Object {
  def json(data: js.Any): Response = js.native
}

@js.native
trait BunServer extends js.Object {
  val port: Int = js.native
}

@js.native @JSGlobal("Bun")
object Bun extends js.Object {
  def serve(options: js.Any): BunServer = js.native
}

// ---- Server ----

object Main {
  private def handleRequest(req: Request): Response = {
    val url = new URL(req.url)
    url.pathname match {
      case "/api/hello" =>
        Response.json(js.Dynamic.literal(
          message = "Hello from Scala.js on Bun!"
        ))
      case "/api/time" =>
        Response.json(js.Dynamic.literal(
          time = new js.Date().toISOString()
        ))
      case _ =>
        new Response(
          "Welcome to the Mill Bun Plugin server!\n\n" +
          "Try:\n" +
          "  GET /api/hello  - greeting\n" +
          "  GET /api/time   - current time\n"
        )
    }
  }

  def main(args: Array[String]): Unit = {
    val server = Bun.serve(js.Dynamic.literal(
      port = 3000,
      fetch = (handleRequest(_)): js.Function1[Request, Response]
    ))
    println(s"Server running at http://localhost:${server.port}")
  }
}
