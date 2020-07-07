package examples

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws.TextMessage
import examples.BitmexExample.HandlerProtocolWrapper
import websokket.{Client, ClientGraphBuilder, FlowMaterialiser}

import scala.concurrent.duration._

object BitmexExample {
  sealed trait Command
  final case object Stop extends Command

  private final case object ClientTerminated extends Command
  private final case object CloseTimedOut    extends Command

  private final case class SendResultWrapper(sendResult: Client.SendMessage.Result) extends Command
  private final case class HandlerProtocolWrapper(protocolMsg: Client.HandlerProtocol)
      extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val sendResultAdapter      = context.messageAdapter(SendResultWrapper)
        val handlerProtocolAdapter = context.messageAdapter(HandlerProtocolWrapper)

        val uri = Uri("wss://www.bitmex.com/realtime")
        val flowMaterialiser: FlowMaterialiser =
          FlowMaterialiser.defaultMaterialiser(uri)

        val graphBuilderConfig = ClientGraphBuilder.DefaultBuilderConfig()
        val graphBuilder =
          ClientGraphBuilder.defaultBuilder(graphBuilderConfig, flowMaterialiser)

        val clientConfig = Client.Config()

        val webSocketClient =
          context.spawn(Client(clientConfig, graphBuilder, handlerProtocolAdapter),
                        "websocket-client")

        context.watchWith(webSocketClient, ClientTerminated)

        new BitmexExample(context, timers, webSocketClient, sendResultAdapter).start()
      }
    }
}

class BitmexExample private (context: ActorContext[BitmexExample.Command],
                             timers: TimerScheduler[BitmexExample.Command],
                             webSocketClient: ActorRef[Client.Command],
                             sendResultAdapter: ActorRef[Client.SendMessage.Result]) {

  import Client.HandlerProtocol.ConnectResult

  def start(): Behavior[BitmexExample.Command] = {
    webSocketClient ! Client.Connect

    waitForConnect()
  }

  private def waitForConnect(): Behavior[BitmexExample.Command] =
    Behaviors.receiveMessage {
      case HandlerProtocolWrapper(protocolMsg) =>
        protocolMsg match {
          case _: ConnectResult.Succeeded =>
            context.log.info("Connection succeeded")

            // Connect succeeded so send subscribe message
            val sub = "{\"op\": \"subscribe\", \"args\": [\"trade:XBTUSD\"]}"
            webSocketClient ! Client.SendMessage(0, TextMessage.Strict(sub), sendResultAdapter)

            handleMessages()

          case _: ConnectResult.Rejected | ConnectResult.TimedOut | _: ConnectResult.Unknown =>
            context.log.error("Failed to connect: {}", protocolMsg)
            Behaviors.stopped

          case Client.HandlerProtocol.MessageReceived(socketMsg) =>
            context.log.info("Message received: {}", socketMsg.text)
            Behaviors.same
        }

      case BitmexExample.Stop =>
        context.log.info("Closing connection")
        webSocketClient ! Client.Close
        closing()

      case BitmexExample.ClientTerminated =>
        context.log.info("WebSocket connection terminated unexpectedly")
        Behaviors.stopped

      case _: BitmexExample.SendResultWrapper =>
        context.log.error("Received a SendResultWrapper message but no messages sent")
        throw new IllegalStateException()

      case BitmexExample.CloseTimedOut =>
        context.log.error("Close time out message received but close not requested")
        throw new IllegalStateException()
    }

  private def handleMessages(): Behavior[BitmexExample.Command] =
    Behaviors.receiveMessage {
      case HandlerProtocolWrapper(protocolMsg) =>
        protocolMsg match {
          case _: ConnectResult.Succeeded | _: ConnectResult.Rejected | ConnectResult.TimedOut |
              _: ConnectResult.Unknown =>
            context.log.error("ConnectResult received while connection already assumed open")
            throw new IllegalStateException()

          case Client.HandlerProtocol.MessageReceived(socketMsg) =>
            context.log.info("Message received: {}", socketMsg.text)
            Behaviors.same
        }

      case BitmexExample.Stop =>
        context.log.info("Closing connection")
        webSocketClient ! Client.Close
        closing()

      case BitmexExample.ClientTerminated =>
        context.log.info("WebSocket connection terminated unexpectedly")
        Behaviors.stopped

      case BitmexExample.SendResultWrapper(result) =>
        result match {
          case _: Client.SendMessage.Result.Enqueued =>
            Behaviors.same
          case _: Client.SendMessage.Result.Failed | _: Client.SendMessage.Result.RetryLater =>
            context.log.error("Failed to send message: {}", result)
            webSocketClient ! Client.Close
            closing()
        }

      case BitmexExample.CloseTimedOut =>
        context.log.error("Close time out message received but close not requested")
        throw new IllegalStateException()
    }

  private def closing(): Behavior[BitmexExample.Command] = {
    timers.startSingleTimer(BitmexExample.CloseTimedOut, BitmexExample.CloseTimedOut, 10.seconds)

    Behaviors.receiveMessage {
      case BitmexExample.ClientTerminated =>
        context.log.info("WebSocket connection closed")
        Behaviors.stopped

      case BitmexExample.CloseTimedOut =>
        context.log.info("WebSocket connection close timed out")
        Behaviors.stopped

      case BitmexExample.Stop =>
        Behaviors.same

      case _: BitmexExample.SendResultWrapper =>
        context.log.error("Received a SendResultWrapper message but no messages sent")
        throw new IllegalStateException()

      case HandlerProtocolWrapper(protocolMsg) =>
        protocolMsg match {
          case _: ConnectResult.Succeeded | _: ConnectResult.Rejected | ConnectResult.TimedOut |
              _: ConnectResult.Unknown =>
            context.log.error("ConnectResult received while connection already assumed open")
            throw new IllegalStateException()

          case Client.HandlerProtocol.MessageReceived(socketMsg) =>
            context.log.info("Message received: {}", socketMsg.text)
            Behaviors.same
        }
    }
  }
}

object BitmexExampleRunner extends App {
  val system = ActorSystem(BitmexExample(), "system")
  Thread.sleep(15000)
  system ! BitmexExample.Stop
}
