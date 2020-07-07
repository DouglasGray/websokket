package websokket

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.ws.{Message, WebSocketUpgradeResponse}
import akka.http.scaladsl.testkit.WSProbe
import akka.stream.scaladsl.Flow

import scala.concurrent.{ExecutionContextExecutor, Future, blocking}

object TestFlowMaterialiser {
  final case class WSProbeAndMaterialiser(wsProbe: WSProbe, materialiser: FlowMaterialiser)

  def apply(webSocketUpgradeResponse: WebSocketUpgradeResponse,
            replyTo: ActorRef[WSProbeAndMaterialiser],
            waitBeforeUpgradeResponse: Long = 0): Behavior[NotUsed] =
    Behaviors.setup { context =>
      implicit val system: akka.actor.ActorSystem = context.system.toClassic

      val wsProbe = WSProbe()
      val materialiser =
        new TestFlowMaterialiser(wsProbe, webSocketUpgradeResponse, waitBeforeUpgradeResponse)

      replyTo ! WSProbeAndMaterialiser(wsProbe, materialiser)

      Behaviors.stopped
    }
}

class TestFlowMaterialiser private (wsProbe: WSProbe,
                                    webSocketUpgradeResponse: WebSocketUpgradeResponse,
                                    waitBeforeUpgradeResponse: Long)
    extends FlowMaterialiser {

  override def materialise()(implicit system: akka.actor.ActorSystem)
    : Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    implicit val ex: ExecutionContextExecutor = system.dispatcher
    wsProbe.flow.mapMaterializedValue(_ =>
      Future {
        blocking {
          Thread.sleep(waitBeforeUpgradeResponse)
        }
        webSocketUpgradeResponse
    })
  }
}
