# Spring WebFlux STOMP Handler

#### A lightweight implementation of the STOMP (1.2↓) protocol for those using or transferring over to Spring WebFlux

## Background

With the addition of Spring's Reactive framework, dubbed WebFlux, many Spring MVC features have either not yet been
implemented or do not plan to get implemented. This leaves a void for some who intend to upgrade their backend
architecture without having to restructure their frontend.

This repository features a <em>very</em> lightweight implementation of a server-side STOMP WebSocket handler (designed 
around the spec outlined [here](http://stomp.github.io/)) written using Spring WebFlux. This implementation checks 
all the required boxes within the spec, but does not fully support every optional feature (i.e. heartbeat-ing).

## Spring WebFlux STOMP API

This STOMP implementation is achieved using three general classes:

 - [```StompMessage.java```](src/main/java/org/github/stomp/data/StompMessage.java)
 - [```StompHandler.java```](src/main/java/org/github/stomp/handler/StompHandler.java)
 - [```AbstractStompHandler.java```](src/main/java/org/github/stomp/handler/AbstractStompHandler.java)

As the name suggests, the ```StompMessage``` is a bare-bones immutable message object used to organize the different
components of standard STOMP frames and handle the conversion from Spring WebFlux's ```WebSocketMessage```. The
```StompHandler``` is a simple interface that provides methods to define specific side effects upon receiving
specific client frames. The methods allow users to have a final look at the responding frames and, while not
suggested, alter them depending on applicable business logic. The ```AbstractStompHandler``` is an abstract class that
defines the core functionality, logic, and implementation of a server-side STOMP handler.

## Intended Use

Each class provided in this repository was designed with generality in mind. This is not to say that this
implementation is complete or flawless, however, changes are not intended to be made to these three files. Instead,
the ```AbstractStompHandler``` should be extended, and methods from the ```StompHandler``` interface should be
overridden. This repository serves as a trivial example of how to use this handler, with the
```SimpleStompHandler``` demonstrating the implementation of potentially tailored business logic.

The ```AbstractStompHandler``` provides multiple utility functions to formulate outbound STOMP frames (listed below),
which are recommended to be used instead of directly constructing a ```StompMessage```. Keep in mind that 
```ERROR``` frames resulting from a non-compliance with the spec and ```RECEIPT``` frames expected in compliance 
with the spec are already generated when appropriate before methods in the ```StompHandler``` are called.

To create ```MESSAGE``` frames:
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, String body)```
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, Map<String, List<String>> userDefinedHeaders)```
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders)```
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, Map<String, List<String>> userDefinedHeaders, String body)```
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, String body)```
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body)```
- ```AbstractStompHandler#makeMessage(String sessionId, String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body)```

To create ```RECEIPT``` frames:
- ```AbstractStompHandler#makeReceipt(String sessionId, StompMessage inbound)```

To create ```ERROR``` frames:
- ```AbstractStompHandler#makeError(String sessionId, StompMessage inbound, MultiValueMap<String, String> userDefinedHeaders, String errorHeader, String errorBody)```

Some of the methods defined in the ```StompHandler``` are guaranteed to be provided with a non-null outbound frame 
(demonstrated by the default implementation using ```Mono#just(T data)``` as opposed to ```Mono#justOrEmpty(T data)```).
However, all the methods (except for ```StompHandler#onError(WebSocketSession session, StompMessage inbound, 
StompMessage outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, 
Tuple2<String, StompMessage>> messagesCache)``` of course) are guaranteed to be provided with either a non-```ERROR``` 
or a null frame, thus eliminating the need to check for errors.

## Example

This repository contains an example of an application based on the STOMP protocol.

The example can be built by running the following from the repository root directory:
```
./gradlew clean build
```

To run the compiled JAR, run the following from the repository root directory:
```
$JAVA_HOME/bin/java.exe -jar ./build/libs/SpringWebFluxSTOMPHandler-1.0-SNAPSHOT.jar
```

To interact with this example, open the [```websocket_test.html```](websocket_test.html) in your favorite browser and 
open up the console.


## Disclaimer

This software is not complete and designed by a 3rd party source. While it is currently in use 
in a production environment, users should utilize at their own risk.

For those designing new architectures, the Spring Team recommends the use of the newly created 
[```RSocket```](https://docs.spring.io/spring-framework/docs/current/reference/html/rsocket.html) seeing as this is 
a 1st party feature of the Spring Framework.

## TODO
 - Implement heartbeat functionality
 - Include unit tests

## Comments or Questions

[Contact Me](mailto:markkoszykowski@gmail.com)