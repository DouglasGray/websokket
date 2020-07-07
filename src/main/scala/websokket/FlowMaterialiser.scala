package websokket

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.stream.scaladsl.Flow

import scala.concurrent.Future

/* Creates the core bidirectional websocket flow. The default implementation
 * below creates the usual upgraded TCP connection */
trait FlowMaterialiser {
  def materialise()(
      implicit system: akka.actor.ActorSystem
  ): Flow[Message, Message, Future[WebSocketUpgradeResponse]]
}

object FlowMaterialiser {
  def defaultMaterialiser(uri: Uri): FlowMaterialiser =
    new DefaultFlowMaterialiser(uri)

  private class DefaultFlowMaterialiser(uri: Uri) extends FlowMaterialiser {
    override def materialise()(
        implicit system: akka.actor.ActorSystem
    ): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
      Http().webSocketClientFlow(WebSocketRequest(uri))
    }
  }
}
