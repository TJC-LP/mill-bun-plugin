import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Minimal facades for Bun's runtime and Web standard APIs.

@js.native @JSGlobal
class URL(input: String) extends js.Object {
  val pathname: String = js.native
  val searchParams: URLSearchParams = js.native
}

@js.native @JSGlobal
class URLSearchParams extends js.Object {
  def get(name: String): String = js.native
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
