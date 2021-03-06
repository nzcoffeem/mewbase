package example.gettingstarted.commandrest

import cats.effect.{Effect, IO}
import com.typesafe.config.ConfigFactory
import fs2.StreamApp
import io.circe.Json
import io.mewbase.binders.BinderStore
import io.mewbase.bson.BsonObject
import io.mewbase.cqrs.CommandManager
import io.mewbase.eventsource.{EventSink, EventSource}
import io.mewbase.projection.ProjectionManager
import io.mewbase.rest.{RestServiceAction, RestServiceAdaptor}
import io.mewbase.rest.http4s.Http4sRestServiceActionVisitor
import org.http4s.HttpService
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import org.http4s.server.blaze.BlazeBuilder
import io.mewbase.rest.http4s.BsonEntityCodec._

import scala.concurrent.ExecutionContext.Implicits.global

/*
This example shows how to use a expose a mewbase component (CommandManager) through an
existing http4s service
 */

class HelloWorldService(commandManager: CommandManager) extends Http4sDsl[IO] {

  val actionVisitor = new Http4sRestServiceActionVisitor[IO]

  val helloWorldService: HttpService[IO] = HttpService[IO] {

    case GET -> Root / "hello" / name =>
      Ok(Json.obj("message" -> Json.fromString(s"hello $name")))

    case req@POST -> Root / "buy" =>
      req.as[BsonObject].flatMap { body =>
        actionVisitor.visit(RestServiceAction.executeCommand(commandManager, Main.buyCommand.getName, body))
      }

    case GET -> Root / "purchase" =>
      actionVisitor.visit(RestServiceAction.listDocumentIds(Main.binderStore, Main.projectionOutputChannel))

    case GET -> Root / "purchase" / purchaseNumber =>
      actionVisitor.visit(RestServiceAction.retrieveSingleDocument(Main.binderStore, Main.projectionOutputChannel, purchaseNumber))

  }

}

object Main extends StreamApp[IO] {

  val binderStore: BinderStore = BinderStore.instance()
  val eventSink: EventSink = EventSink.instance()
  val commandManager: CommandManager = CommandManager.instance(eventSink)
  val eventSource: EventSource = EventSource.instance()
  val projectionManager: ProjectionManager = ProjectionManager.instance(eventSource, binderStore)
  val projectionOutputChannel = "processed_purchase_events"


  val buyCommand =
    commandManager
        .commandBuilder()
        .named("buy")
        .emittingTo("purchase_events")
        .as { params =>
          val event = new BsonObject
          event.put("product", params.getString("product")) // product copied from incoming HTTP post
          event.put("quantity", params.getInteger("quantity")) // quantity copied from incoming HTTP post
          event.put("action", "BUY") // action is always BUY
          event
        }
        .create()


  projectionManager
    .builder()
    .named("projection1")
    .projecting(buyCommand.getOutputChannel)
    .onto(projectionOutputChannel)
    .filteredBy(event => event.getBson.containsKey("action"))
    .identifiedBy(event => event.getEventNumber.toString)
    .as((current, event) => {
      event.getBson
    })
    .create().get()


  override def stream(args: List[String], requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] =
    BlazeBuilder[IO]
        .bindHttp(8080, "0.0.0.0")
        .mountService(new HelloWorldService(commandManager).helloWorldService, "/")
        .serve
}
