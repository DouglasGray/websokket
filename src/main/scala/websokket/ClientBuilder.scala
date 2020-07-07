package websokket

import akka.actor.typed.{ActorRef, Behavior}

/* Given an implementor of the handler protocol, build a WebSocket client */
trait ClientBuilder {
  def build(handler: ActorRef[Client.HandlerProtocol]): Behavior[Client.Command]
}

object ClientBuilder {
  def defaultBuilder(clientConfig: Client.Config,
                     graphBuilder: ClientGraphBuilder): ClientBuilder = {
    new DefaultClientBuilder(clientConfig, graphBuilder)
  }

  private class DefaultClientBuilder(clientConfig: Client.Config, graphBuilder: ClientGraphBuilder)
      extends ClientBuilder {
    override def build(handler: ActorRef[Client.HandlerProtocol]): Behavior[Client.Command] = {
      Client(clientConfig, graphBuilder, handler)
    }
  }
}
