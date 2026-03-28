import scala.scalajs.js

object Main {
  private var visitCount = 0

  private def handleRequest(req: Request): Response = {
    val url = new URL(req.url)

    url.pathname match {
      case path if path.startsWith("/api/greet/") =>
        val name = path.stripPrefix("/api/greet/")
        Response.json(Greeting(name, s"Hello, $name! Greetings from Scala.js on Bun."))

      case "/api/greet" =>
        Response.json(Greeting("world", "Hello, world! Pass a name: /api/greet/YourName"))

      case "/api/counter" =>
        visitCount += 1
        Response.json(Counter(visitCount))

      case _ =>
        new Response(
          "Fullstack Scala.js API\n\n" +
          "Endpoints:\n" +
          "  GET /api/greet/:name  - personalized greeting\n" +
          "  GET /api/counter      - visit counter\n"
        )
    }
  }

  def main(args: Array[String]): Unit = {
    val server = Bun.serve(js.Dynamic.literal(
      port = 3000,
      fetch = (handleRequest(_)): js.Function1[Request, Response]
    ))
    println(s"API server running at http://localhost:${server.port}")
  }
}
