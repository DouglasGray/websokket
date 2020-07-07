### WebSokket

A WebSocket client using Akka actors and streams. 

An example is located in ```src/main/scala/examples/BitmexExample.scala``` which goes
through the basic process of waiting for the connection result and subscribing to a stream.

The main class is the ```Client```. Once spawned send either a ```Client.Connect``` 
or ```Client.SendMessage``` command to start the connection. It will send a 
```Client.HandlerProtocol.ConnectResult``` message followed by 
```Client.HandlerProtocol.MessageReceived``` messages (assuming that connection
succeeded).

When creating an instance of ```Client```, it takes a ```Client.Config```, 
a ```ClientGraphBuilder``` and an ```ActorRef[Client.HandlerProtocol]```.
A couple notes on the latter two arguments below:

- ```ActorRef[Client.HandlerProtocol```: This is the way the client
communicates. Whatever listens to the incoming websocket messages should
process these messages. A possible way is below, where you might handle the 
```HandlerProtocolWrapper``` in the usual ```Behaviors.receiveMessage``` block.

```
object MyHandler {
    sealed trait Command
    ...
    private final case class HandlerProtocolWrapper(protocolMsg: Client.HandlerProtocol)
          extends Command

    def apply(): Behavior[Command] =
        Behaviors.setup { context =>
            val handlerProtocolAdapter = context.messageAdapter(HandlerProtocolWrapper)
            ...
            val webSocketClient =
                      context.spawn(Client(Client.Config(), clientGraphBuilder, handlerProtocolAdapter),
                                    "websocket-client")
            ...
            
            Behaviors.receiveMessage {
                case HandlerProtocolWrapper(protocolMsg) =>
                    protocolMsg match {
                        case _: Client.HandlerProtocol.MessageReceived =>
                            // do stuff
                        case _: Client.HandlerProtocol.ConnectResult =>
                            // do other stuff
                    }
                ...
            }
    }
``` 


-  ```ClientGraphBuilder```: When ```build()``` is invoked with a callback from the client
this should build the underlying Akka streams graph for the bidirectional websocket flow.
The provided callback should be used upon receipt of a message from the socket.

    A hopefully sensible default is at ```ClientGraphBuilder.DefaultBuilder``` which is
    can be created by calling the ```ClientGraphBuilder.defaultBuilder()``` function with
    a suitable ```FlowMaterialiser```. The latter just takes a ```Uri``` and builds the
    actual connection. A default is provided at ```FlowMaterialiser.DefaultFlowMaterialiser```
    and can be used together with ```ClientGraphBuilder``` as:
    
```
// Adapter for messages from the WebSocket client
val handlerProtocolAdapter = context.messageAdapter(HandlerProtocolWrapper)

// Creates connection from Uri
val uri = Uri("wss://www.somewhere.com")
    val flowMaterialiser: FlowMaterialiser =
        FlowMaterialiser.defaultMaterialiser(uri)

// Builds Akka streams graph
val graphBuilderConfig = ClientGraphBuilder.DefaultBuilderConfig()
        val graphBuilder =
          ClientGraphBuilder.defaultBuilder(graphBuilderConfig, flowMaterialiser)

// Start websocket client
val clientConfig = Client.Config()
val webSocketClient =
  context.spawn(Client(clientConfig, graphBuilder, handlerProtocolAdapter),
                "websocket-client")
``` 