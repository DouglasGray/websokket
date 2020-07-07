package websokket

import akka.Done
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.ws.{TextMessage, WebSocketUpgradeResponse}
import akka.http.scaladsl.model.{HttpHeader, StatusCode, StatusCodes}
import akka.stream.QueueOfferResult
import akka.stream.scaladsl.SourceQueueWithComplete

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success, Try}

object Client {
  /* Handler to WebSocket client protocol */
  sealed trait Command
  final case object Connect extends Command
  final case class SendMessage(msgId: Long,
                               msg: TextMessage.Strict,
                               replyTo: ActorRef[SendMessage.Result])
      extends Command
  final case object Close extends Command

  object SendMessage {
    sealed trait Result {
      def msgId: Long
    }
    object Result {
      final case class Enqueued(msgId: Long)                   extends Result
      final case class RetryLater(msgId: Long, reason: String) extends Result
      final case class Failed(msgId: Long, reason: String)     extends Result
    }
  }

  /* WebSocket client to handler protocol */
  sealed trait HandlerProtocol
  object HandlerProtocol {
    final case class MessageReceived(socketMsg: TextMessage.Strict) extends HandlerProtocol

    sealed trait ConnectResult extends HandlerProtocol
    object ConnectResult {
      final case class Succeeded(headers: Seq[HttpHeader]) extends ConnectResult
      final case class Rejected(statusCode: StatusCode, headers: Seq[HttpHeader])
          extends ConnectResult
      final case object TimedOut              extends ConnectResult
      final case class Unknown(ex: Throwable) extends ConnectResult
    }
  }

  /* Internal messages */
  private final case class UpgradeResponse(
      triedUpgradeResponse: Try[WebSocketUpgradeResponse]
  ) extends Command
  private final case object ConnectTimedOut                                       extends Command
  private final case class MessageReceived(triedMessage: Try[TextMessage.Strict]) extends Command
  private final case class MessageOfferResult(
      msgId: Long,
      replyTo: ActorRef[Client.SendMessage.Result],
      triedQueueOffer: Try[QueueOfferResult]
  ) extends Command
  private final case object ConnectionClosed extends Command
  private final case object CloseTimedOut    extends Command

  /* Client config class */
  final case class Config(connectTimeout: FiniteDuration = 15.seconds,
                          closeTimeout: FiniteDuration = 5.seconds,
                          stashBufferCapacity: Int = 512) {
    require(stashBufferCapacity > 0)
  }

  def apply(
      config: Config,
      webSocketGraphBuilder: ClientGraphBuilder,
      socketHandler: ActorRef[HandlerProtocol]
  ): Behavior[Client.Command] =
    Behaviors.withStash(config.stashBufferCapacity) { stashBuffer =>
      Behaviors.withTimers { timers =>
        Behaviors.setup { context =>
          new Client(config, socketHandler, context, timers, stashBuffer)
            .idle(webSocketGraphBuilder)
        }
      }
    }
}

class Client private (config: Client.Config,
                      socketHandler: ActorRef[Client.HandlerProtocol],
                      context: ActorContext[Client.Command],
                      timers: TimerScheduler[Client.Command],
                      stashBuffer: StashBuffer[Client.Command]) {

  implicit val system: akka.actor.ActorSystem = context.system.toClassic

  import Client.HandlerProtocol

  context.log.info("WebSocket started")

  /* Initial state, waiting on a command to either connect or forward a message */
  def idle(
      webSocketGraphBuilder: ClientGraphBuilder
  ): Behavior[Client.Command] =
    Behaviors.receiveMessage {
      case message @ (Client.Connect | _: Client.SendMessage) =>
        message match {
          case _: Client.SendMessage => stashBuffer.stash(message)
          case _                     =>
        }

        val (sender, receiver, upgradeResponse) =
          webSocketGraphBuilder.build(context.pipeToSelf(_)(Client.MessageReceived))

        context.pipeToSelf(upgradeResponse)(Client.UpgradeResponse)

        connecting(sender, receiver)

      case Client.Close =>
        context.log.info("Close signal received while idle, stopping actor")
        Behaviors.stopped

      case message @ (Client.ConnectTimedOut | Client.ConnectionClosed | Client.CloseTimedOut |
          _: Client.MessageOfferResult | _: Client.MessageReceived | _: Client.UpgradeResponse) =>
        context.log.error("Received an unexpected message while in idle state: {}", message)
        throw new IllegalStateException("Unexpected message type received while in idle state")
    }

  /* Waiting on connect attempt results */
  def connecting(sender: SourceQueueWithComplete[TextMessage],
                 receiver: Future[Done]): Behavior[Client.Command] = {

    context.log.info("Attempting connection")

    // Kick off timeout
    startConnectTimeout()

    Behaviors.receiveMessage {
      case Client.Connect =>
        Behaviors.same

      case message @ Client.SendMessage(msgId, _, replyTo) =>
        if (stashBuffer.isFull) {
          context.log.warn("Buffer full, rejecting internal message {}", message)
          replyTo ! Client.SendMessage.Result.RetryLater(msgId, "Send buffer currently full")
        } else {
          stashBuffer.stash(message)
        }
        Behaviors.same

      case Client.Close =>
        context.log.info(
          "Close signal received while attempting connection, stopping actor"
        )
        Behaviors.stopped

      case Client.UpgradeResponse(triedUpgradeResponse) =>
        triedUpgradeResponse match {
          case Success(upgradeResponse) =>
            val status  = upgradeResponse.response.status
            val headers = upgradeResponse.response.headers

            if (status == StatusCodes.SwitchingProtocols) {
              context.log.info("Connection succeeded")

              // Watch for completion and arrange for a message to be sent if
              // either sender or receiver closes
              context.pipeToSelf(sender.watchCompletion())(
                _ => Client.ConnectionClosed
              )
              context.pipeToSelf(receiver)(_ => Client.ConnectionClosed)

              socketHandler ! HandlerProtocol.ConnectResult.Succeeded(headers)

              cancelConnectTimeout()
              stashBuffer.unstashAll(open(sender))

            } else {
              context.log.error("Connection rejected: {}", upgradeResponse)
              socketHandler ! HandlerProtocol.ConnectResult
                .Rejected(status, headers)

              Behaviors.stopped
            }
          case Failure(exception) =>
            context.log.error("Error during connection attempt: {}", exception)
            socketHandler ! HandlerProtocol.ConnectResult.Unknown(exception)

            Behaviors.stopped
        }

      case Client.ConnectTimedOut =>
        context.log.warn(s"Connection timed out")
        socketHandler ! HandlerProtocol.ConnectResult.TimedOut

        Behaviors.stopped

      case Client.ConnectionClosed =>
        context.log.error("Connection closed unexpectedly")
        Behaviors.stopped

      case message: Client.MessageReceived =>
        if (stashBuffer.isFull) {
          context.log.warn("Buffer full, rejecting socket message {}", message)
        } else {
          stashBuffer.stash(message)
        }
        Behaviors.same

      case message @ (Client.CloseTimedOut | _: Client.MessageOfferResult) =>
        context.log.error("Received an unexpected message while in connecting state: {}", message)
        throw new IllegalStateException(
          "Unexpected message type received while in connecting state")
    }
  }

  /* Connection is open */
  def open(
      sender: SourceQueueWithComplete[TextMessage]
  ): Behavior[Client.Command] = {
    context.log.info("WebSocket connection open")

    Behaviors.receiveMessage {
      case Client.Connect =>
        Behaviors.same

      case Client.SendMessage(msgId, message, replyTo) =>
        val offerResultFut = sender.offer(message)
        context.pipeToSelf(offerResultFut)(
          Client.MessageOfferResult(msgId, replyTo, _)
        )
        Behaviors.same

      case Client.Close =>
        context.log.info("Close signal received")
        // Complete the sender channel to signal closure for peer
        sender.complete()
        closing()

      case Client.MessageOfferResult(msgId, replyTo, triedQueueOffer) =>
        import Client.SendMessage.Result._

        triedQueueOffer match {
          case Success(offerResult) =>
            offerResult match {
              case QueueOfferResult.Enqueued =>
                replyTo ! Enqueued(msgId)
                Behaviors.same

              case QueueOfferResult.Dropped =>
                replyTo ! RetryLater(msgId, "Send buffer currently full")
                Behaviors.same

              case _ =>
                replyTo ! Failed(msgId, "Socket closed")
                Behaviors.stopped
            }
          case Failure(_) =>
            replyTo ! Failed(msgId, "Socket closed")
            Behaviors.stopped
        }

      case Client.MessageReceived(triedMessage) =>
        triedMessage match {
          case Success(socketMessage) =>
            socketHandler ! HandlerProtocol.MessageReceived(socketMessage)
          case Failure(exception) =>
            context.log.error("Failed to process websocket message", exception)
        }
        Behaviors.same

      case Client.ConnectionClosed =>
        context.log.error("Connection closed unexpectedly")
        Behaviors.stopped

      case message @ (Client.ConnectTimedOut | Client.CloseTimedOut | _: Client.UpgradeResponse) =>
        context.log.error("Received an unexpected message while in open state: {}", message)
        throw new IllegalStateException("Unexpected message type received while in open state")
    }
  }

  /* Closing state, waiting on either the connection to close or to hit timeout */
  def closing(): Behavior[Client.Command] = {
    // First start close timeout
    startCloseTimeout()

    Behaviors.receiveMessage {
      case Client.SendMessage(msgId, _, replyTo) =>
        replyTo ! Client.SendMessage.Result
          .Failed(msgId, "Socket closing")
        Behaviors.same

      case Client.MessageReceived(triedMessage) =>
        triedMessage match {
          case Success(socketMessage) =>
            socketHandler ! HandlerProtocol.MessageReceived(socketMessage)
          case Failure(exception) =>
            context.log.error("Failed to process websocket message", exception)
        }
        Behaviors.same

      case Client.CloseTimedOut =>
        context.log.info("Close process timed out, stopping actor")
        Behaviors.stopped

      case Client.ConnectionClosed =>
        context.log.info("Close process completed, stopping actor")
        Behaviors.stopped

      case _: Client.MessageOfferResult | Client.Close | Client.Connect =>
        Behaviors.same

      case message @ (Client.ConnectTimedOut | _: Client.UpgradeResponse) =>
        context.log.error("Received an unexpected message while in closing state: {}", message)
        throw new IllegalStateException("Unexpected message type received while in closing state")

    }
  }

  private def startConnectTimeout(): Unit = {
    if (!timers.isTimerActive(Client.ConnectTimedOut)) {
      timers.startSingleTimer(
        Client.ConnectTimedOut,
        Client.ConnectTimedOut,
        config.connectTimeout
      )
    }
  }

  private def cancelConnectTimeout(): Unit = {
    if (timers.isTimerActive(Client.ConnectTimedOut)) {
      timers.cancel(Client.ConnectTimedOut)
    }
  }

  private def startCloseTimeout(): Unit = {
    if (!timers.isTimerActive(Client.CloseTimedOut)) {
      timers.startSingleTimer(Client.CloseTimedOut, Client.CloseTimedOut, config.closeTimeout)
    }
  }
}
