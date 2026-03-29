package backend

object Backend extends cask.MainRoutes {
  override def port: Int = sys.env.getOrElse("PORT", "8080").toInt

  @cask.get("/api/greet/:name")
  def greet(name: String): ujson.Value =
    ujson.Obj("message" -> s"Hello, $name! From the Scala/Cask JVM backend.")

  @cask.get("/api/time")
  def time(): ujson.Value =
    ujson.Obj("time" -> java.time.Instant.now().toString)

  @cask.get("/")
  def index(): cask.Response[String] = {
    val stream = getClass.getClassLoader.getResourceAsStream("webapp/index.html")
    val html = new String(stream.readAllBytes(), "UTF-8")
    cask.Response(data = html, headers = Seq("Content-Type" -> "text/html"))
  }

  @cask.staticResources("/static")
  def static(): String = "webapp"

  initialize()
}
