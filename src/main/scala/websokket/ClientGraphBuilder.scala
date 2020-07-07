package websokket

import akka.Done
import akka.http.scaladsl.coding.{Coder, Deflate}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, WebSocketUpgradeResponse}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContextExecutor, Future}

/* Any implementor of this should build the akka streams graph (typically using the
 * ClientFlowMaterialiser), and given the callback passed through 'build',
 * use this to forward messages from the connection. An example implementor is below
 * in the 'DefaultBuilder' private class */
trait ClientGraphBuilder {
  def build(onMessageCallback: Future[TextMessage.Strict] => Any)(
      implicit system: akka.actor.ActorSystem
  ): (SourceQueueWithComplete[TextMessage], Future[Done], Future[WebSocketUpgradeResponse])
}

object ClientGraphBuilder {
  final case class DefaultBuilderConfig(frameWaitTimeout: FiniteDuration = 5.seconds,
                                        sendQueueCapacity: Int = 512,
                                        encoding: Coder = Deflate)

  def defaultBuilder(config: DefaultBuilderConfig,
                     flowMaterialiser: FlowMaterialiser): ClientGraphBuilder =
    new DefaultBuilder(config, flowMaterialiser)

  private class DefaultBuilder(config: DefaultBuilderConfig, flowMaterialiser: FlowMaterialiser)
      extends ClientGraphBuilder {
    def build(onMessageCallback: Future[TextMessage.Strict] => Any)(
        implicit system: akka.actor.ActorSystem
    ): (SourceQueueWithComplete[TextMessage], Future[Done], Future[WebSocketUpgradeResponse]) = {

      implicit val ec: ExecutionContextExecutor = system.dispatcher

      // Sink to gather messages coming in from the socket
      // May need to do a bit of deserialisation
      val incoming: Sink[Message, Future[Done]] =
        Sink.foreach[Message] {
          case textMessage: TextMessage =>
            // toStrict() will return immediately if the message is already complete
            val completeMessage = textMessage.toStrict(config.frameWaitTimeout)
            onMessageCallback(completeMessage)

          case binaryMessage: BinaryMessage =>
            val completeMessage = binaryMessage
              .toStrict(config.frameWaitTimeout)
              .map(bytes => config.encoding.decode(bytes.data))
              .map(
                futBytes => futBytes.map(bytes => TextMessage.Strict(bytes.utf8String))
              )
            onMessageCallback(completeMessage.flatten)
        }

      // Source which the client will use to send messages
      val outgoing =
        Source
          .queue[TextMessage](
            config.sendQueueCapacity,
            OverflowStrategy.dropNew
          )

      val webSocketFlow = flowMaterialiser.materialise()

      // Put it all together
      val ((sender, upgradeResponse), receiver) =
        outgoing
          .viaMat(webSocketFlow)(Keep.both)
          .toMat(incoming)(Keep.both)
          .run()

      (sender, receiver, upgradeResponse)
    }
  }
}
