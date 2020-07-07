package websokket

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.ws.{TextMessage, ValidUpgrade, WebSocketUpgradeResponse}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.testkit.WSProbe
import org.scalatest.wordspec.AnyWordSpecLike
import websokket.TestFlowMaterialiser.WSProbeAndMaterialiser

import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor

class ClientTest extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  def setUp(config: Client.Config,
            upgradeResponse: WebSocketUpgradeResponse,
            waitBeforeUpgradeResponse: Long = 0)
    : (WSProbe, TestProbe[Client.HandlerProtocol], Behavior[Client.Command]) = {

    val startUpReceiver = testKit.createTestProbe[WSProbeAndMaterialiser]("ws-probe-receiver")

    testKit.spawn(
      TestFlowMaterialiser(upgradeResponse, startUpReceiver.ref, waitBeforeUpgradeResponse))

    val wsProbeAndMaterialiser: WSProbeAndMaterialiser = startUpReceiver.receiveMessage()

    val webSocketGraphBuilder =
      ClientGraphBuilder.defaultBuilder(ClientGraphBuilder.DefaultBuilderConfig(),
                                        wsProbeAndMaterialiser.materialiser)

    val handlerProbe = testKit.createTestProbe[Client.HandlerProtocol]()

    val webSocketClient = Client(config, webSocketGraphBuilder, handlerProbe.ref)

    (wsProbeAndMaterialiser.wsProbe, handlerProbe, webSocketClient)
  }

  "Client" must {
    "Forward success message on valid upgrade response" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val (_, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect

      handlerProbe.receiveMessage() match {
        case _: Client.HandlerProtocol.ConnectResult.Succeeded =>
        case other =>
          throw new RuntimeException(s"Expected connect attempt to succeed, got $other")
      }
    }
  }

  "Client" must {
    "Forward failure message on invalid upgrade response" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.BadRequest), None)

      val (_, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect

      handlerProbe.receiveMessage() match {
        case Client.HandlerProtocol.ConnectResult.Rejected(statusCode, _) =>
          assert(statusCode == StatusCodes.BadRequest)
        case other =>
          throw new RuntimeException(s"Expected connect attempt to fail, got $other")
      }
    }
  }

  "Client" must {
    "Forward failure message on rejected upgrade response" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.BadRequest), None)

      val (_, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect

      handlerProbe.receiveMessage() match {
        case Client.HandlerProtocol.ConnectResult.Rejected(statusCode, _) =>
          assert(statusCode == StatusCodes.BadRequest)
        case other =>
          throw new RuntimeException(s"Expected connect attempt to fail, got $other")
      }
    }
  }

  "Client" must {
    "Forward failure message on timed out connection attempt" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val config = Client.Config(connectTimeout = 0.seconds)

      val (_, handlerProbe, clientBehaviour) = setUp(config, upgradeResponse, 5000)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect

      handlerProbe.receiveMessage() match {
        case Client.HandlerProtocol.ConnectResult.TimedOut =>
        case other =>
          throw new RuntimeException(s"Expected connect attempt to time out, got $other")
      }
    }
  }

  "Client" must {
    "Forward message and return reply" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val (wsProbe, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse)

      val sendMsgResultProbe = testKit.createTestProbe[Client.SendMessage.Result]()

      val webSocketClient = testKit.spawn(clientBehaviour)

      val msgId         = 0
      val clientMessage = TextMessage.Strict("Hello")
      val serverMessage = TextMessage.Strict("Hi")

      // Client sends message to server
      webSocketClient ! Client.SendMessage(msgId, clientMessage, sendMsgResultProbe.ref)

      // This should start the connection process
      handlerProbe.receiveMessage() match {
        case _: Client.HandlerProtocol.ConnectResult.Succeeded =>
        case other =>
          throw new RuntimeException(s"Expected connect attempt to succeed, got $other")
      }

      // Expect a response to our send attempt, whether it made it on the wire or not
      sendMsgResultProbe.expectMessage(Client.SendMessage.Result.Enqueued(msgId))

      // Server side, expect client message
      wsProbe.expectMessage(clientMessage.text)

      // Server sends a response
      wsProbe.sendMessage(serverMessage)

      // Client should receive the server message
      handlerProbe.receiveMessage() match {
        case Client.HandlerProtocol.MessageReceived(msg) =>
          assert(msg.text.compareTo(serverMessage.text) == 0)
        case other =>
          throw new RuntimeException(s"Expected server message to be forwarded, got $other")
      }
    }
  }

  "Client" must {
    "Terminate when sent a close message in idle state" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val (_, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse, 5000)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Close

      handlerProbe.expectTerminated(webSocketClient)
    }
  }

  "Client" must {
    "Terminate when sent a close message while connecting" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val (_, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse, 5000)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect
      webSocketClient ! Client.Close

      handlerProbe.expectTerminated(webSocketClient)
    }
  }

  "Client" must {
    "Terminate when sent a close message after connecting" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val (_, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect

      handlerProbe.receiveMessage() match {
        case _: Client.HandlerProtocol.ConnectResult.Succeeded =>
        case other =>
          throw new RuntimeException(s"Expected connect attempt to succeed, got $other")
      }

      webSocketClient ! Client.Close

      handlerProbe.expectTerminated(webSocketClient)
    }
  }

  "Client" must {
    "Terminate when connection closes" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val upgradeResponse =
        ValidUpgrade(HttpResponse(status = StatusCodes.SwitchingProtocols), None)

      val (wsProbe, handlerProbe, clientBehaviour) = setUp(Client.Config(), upgradeResponse)

      val webSocketClient = testKit.spawn(clientBehaviour)

      webSocketClient ! Client.Connect

      handlerProbe.receiveMessage() match {
        case _: Client.HandlerProtocol.ConnectResult.Succeeded =>
        case other =>
          throw new RuntimeException(s"Expected connect attempt to succeed, got $other")
      }

      wsProbe.sendCompletion()

      handlerProbe.expectTerminated(webSocketClient)
    }
  }
}
