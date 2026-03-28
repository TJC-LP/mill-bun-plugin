import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Shared data types used by both server and frontend.
  *
  * Defined as JS traits so they serialize naturally to/from JSON
  * across the network boundary.
  */
trait Greeting extends js.Object {
  val name: String
  val message: String
  val timestamp: Double
}

object Greeting {
  def apply(name: String, message: String): Greeting =
    js.Dynamic.literal(
      name = name,
      message = message,
      timestamp = js.Date.now()
    ).asInstanceOf[Greeting]
}

trait Counter extends js.Object {
  val count: Int
}

object Counter {
  def apply(count: Int): Counter =
    js.Dynamic.literal(count = count).asInstanceOf[Counter]
}
