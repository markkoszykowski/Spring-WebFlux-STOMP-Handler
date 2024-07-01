# Spring WebFlux STOMP Handler

#### A lightweight implementation of the STOMP (1.2↓) protocol for those using or transferring over to Spring WebFlux

## Background

With the addition of Spring's Reactive framework, dubbed WebFlux, many Spring MVC features have either not yet been
implemented or do not plan to get implemented. This leaves a Flux&lt;Void&gt; for some who intend to upgrade their
backend
architecture without having to restructure their frontend.

This repository features a <em>very</em> lightweight implementation of a server-side STOMP WebSocket handler (designed
around the spec outlined [here](http://stomp.github.io/)) written using Spring WebFlux. This implementation checks
all the required boxes within the spec, but does not fully support every optional feature (i.e. heartbeat-ing).

## Spring WebFlux STOMP API

This STOMP implementation is achieved using five general classes:

- [```StompConfig.java```](src/main/java/io/github/stomp/StompConfig.java) - A basic Spring Configuration class
  used to configure multiple backend handlers, each with a different path.
- [```StompFrame.java```](src/main/java/io/github/stomp/StompFrame.java) - A bare-bones immutable frame object
  used to organize the different components of standard STOMP frames and handle the conversion to and from Spring
  WebFlux's ```WebSocketMessage```.
- [```StompHandler.java```](src/main/java/io/github/stomp/StompHandler.java) - The backbone class that defines
  the core functionality, logic, and implementation of a server-side STOMP handler.
- [```StompServer.java```](src/main/java/io/github/stomp/StompServer.java) - A minimalistic interface that
  provides methods to define side effects upon receiving specific client frames. The methods allow users to have
  a final look at the responding frames and, while not suggested, alter them depending on applicable business logic.
- [```StompUtils.java```](src/main/java/io/github/stomp/StompUtils.java) - A utility class intended to supply
  convenient functions to construct server-side STOMP frames.

## Intended Use

Each class provided in this repository was designed with generality in mind. This is not to say that this
implementation is complete or flawless, however, changes are not intended to be made to these five files. Instead,
the ```StompServer``` should be implemented as many times as applicable for the specific server in mind. This repository
serves as a trivial example of how to use this handler, with the ```SimpleStompServer``` and ```ComplexStompServer```
demonstrating the implementations of potentially tailored business logic.

The ```StompUtils``` provides multiple utility functions to formulate outbound STOMP frames (listed below),
which are recommended to be used instead of directly constructing a ```StompMessage```. Keep in mind that
```ERROR``` frames resulting from a non-compliance with the spec and ```RECEIPT``` frames expected in compliance
with the spec are already generated when appropriate before methods in the ```StompServer``` implementations are called.

To create ```MESSAGE``` frames:

- ```StompUtils#makeMessage(String destination, String subscription, String body)```
- ```StompUtils#makeMessage(String destination, String subscription, MimeType contentType, byte[] body)```
- ```StompUtils#makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders)```
- ```StompUtils#makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders)```
- ```StompUtils#makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, String body)```
- ```StompUtils#makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, String body)```
- ```StompUtils#makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body)```
- ```StompUtils#makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body)```

To create ```RECEIPT``` frames:

- ```StompUtils#makeReceipt(StompFrame inbound)```

To create ```ERROR``` frames:

- ```StompUtils#makeError(StompFrame inbound, String errorHeader)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, String body)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, MimeType contentType, byte[] body)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders, String body)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders, String body)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body)```
- ```StompUtils#makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body)```

Some of the methods defined in the ```StompServer``` are guaranteed to be provided with a non-null outbound frame
(demonstrated by the default implementation using ```Mono#just(T data)``` as opposed to ```Mono#justOrEmpty(T data)```).
However, all the methods (except
for ```StompServer#onError(WebSocketSession session, StompFrame inbound, StompFrame outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, StompFrame> messagesCache)```
of course) are guaranteed to be provided with either a non-```ERROR```
or a null frame, thus eliminating the need to check for errors.

## Example

This repository contains an example of an application based on the STOMP protocol.

The example can be built by running the following from the repository root directory:

```
./gradlew clean build
```

To run the compiled JAR, run the following from the repository root directory:

```
$JAVA_HOME/bin/java -jar ./build/libs/spring-webflux-stomp-1.0-SNAPSHOT.jar
```

To interact with this example, open the [```websocket_test.html```](src/test/resources/websocket_test.html)
or [```websocket_error_test.html```](src/test/resources/websocket_error_test.html) in your
favorite browser and open up the console.

## Disclaimer

This software is not complete and designed by a 3rd party source. While it is currently in use
in a production environment, users should utilize at their own risk.

For those designing new architectures, the Spring Team recommends the use of the newly created
[```RSocket```](https://docs.spring.io/spring-framework/reference/rsocket.html) seeing as this is
a 1st party feature of the Spring Framework.

## TODO

- Implement heartbeat functionality
- Include unit tests

## Comments or Questions

[Contact Me](mailto:markkoszykowski@gmail.com)