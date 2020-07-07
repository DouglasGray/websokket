package websokket

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.{TextMessage, ValidUpgrade, WebSocketUpgradeResponse}
import akka.http.scaladsl.testkit.WSProbe
import org.scalatest.wordspec.AnyWordSpecLike
import websokket.TestFlowMaterialiser.WSProbeAndMaterialiser

import scala.concurrent.{Await, ExecutionContextExecutor, Future, Promise}
import scala.concurrent.duration._
import scala.util.Try

class ClientGraphBuilderTest extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  def setUp(upgradeResponse: WebSocketUpgradeResponse): (WSProbe, ClientGraphBuilder) = {

    val startUpReceiver = testKit.createTestProbe[WSProbeAndMaterialiser]("ws-probe-receiver")

    testKit.spawn(TestFlowMaterialiser(upgradeResponse, startUpReceiver.ref))

    val wsProbeAndMaterialiser: WSProbeAndMaterialiser = startUpReceiver.receiveMessage()

    val webSocketGraphBuilder =
      ClientGraphBuilder.defaultBuilder(ClientGraphBuilder.DefaultBuilderConfig(),
                                        wsProbeAndMaterialiser.materialiser)

    (wsProbeAndMaterialiser.wsProbe, webSocketGraphBuilder)
  }

  "ClientGraphBuilder" must {
    "Handle upgrade response and simple message back and forth correctly" in {
      implicit val system: akka.actor.ActorSystem = testKit.system.toClassic
      implicit val ec: ExecutionContextExecutor   = system.dispatcher

      val (wsProbe, webSocketGraphBuilder) =
        setUp(ValidUpgrade(HttpResponse(), None))

      val helloMessage = TextMessage("Hello")

      val recvResult = Promise[Future[TextMessage.Strict]]()

      // This describes what to do when messages are received from the server side
      // In this case that is represented by the wsProbe
      def onMessageComplete(completedMessage: Future[TextMessage.Strict]): Unit = {
        recvResult.complete(Try(completedMessage))
      }

      // Now build the websocket flow / graph
      val (sender, _, upgradeResponse) =
        webSocketGraphBuilder.build(onMessageComplete)

      // Check that the upgrade response is as expected
      Await.result(upgradeResponse, 5.seconds) match {
        case _: ValidUpgrade =>
        case other =>
          throw new RuntimeException(s"Non valid upgrade response: $other")
      }

      // Try send (client) and receive (server/wsProbe) a message
      sender.offer(helloMessage)
      wsProbe.expectMessage(helloMessage.text)

      // Try send (server/wsProbe) and receive (client) a message
      wsProbe.sendMessage(helloMessage)

      Await.result(recvResult.future, 5.seconds) match {
        case fut =>
          Await.result(fut, 5.seconds) match {
            case `helloMessage` =>
            case other          => throw new RuntimeException(s"Unexpected message: $other")
          }
      }

      // Sender closes
      sender.complete()
      wsProbe.expectCompletion()
    }
  }
}
