package org.github.stomp.handler;

import org.github.stomp.data.StompMessage;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public interface StompHandler extends WebSocketHandler {

	// Version Info
	String v1_0 = "1.0";
	String v1_1 = "1.1";
	String v1_2 = "1.2";
	List<String> VERSIONS = List.of(v1_2, v1_1, v1_0);

	@Override
	default List<String> getSubProtocols() {
		return VERSIONS.stream().map(version -> String.format("STOMP %s", version)).collect(Collectors.toList());
	}


	// Add Source from which to transmit data to WebSocket
	default Mono<List<Flux<StompMessage>>> addWebSocketSources(WebSocketSession session) {
		return Mono.empty();
	}

	// Add action for each outbound message
	default void doOnEach(WebSocketSession session, StompMessage outbound) {
	}

	// Add WebSocket close side effects
	default void doFinally(WebSocketSession session, SignalType signal, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
	}

	// Handle Client Frames
	default Mono<StompMessage> onStomp(WebSocketSession session, StompMessage inbound, StompMessage outbound, String version) {
		return Mono.just(outbound);
	}

	default Mono<StompMessage> onConnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, String version) {
		return Mono.just(outbound);
	}

	default Mono<StompMessage> onSend(WebSocketSession session, StompMessage inbound, StompMessage outbound, String destination) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onSubscribe(WebSocketSession session, StompMessage inbound, StompMessage outbound, String destination, String subscriptionId) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onUnsubscribe(WebSocketSession session, StompMessage inbound, StompMessage outbound, String subscriptionId) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onAck(WebSocketSession session, StompMessage inbound, StompMessage outbound, String message, List<Tuple2<String, StompMessage>> ackMessages) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onNack(WebSocketSession session, StompMessage inbound, StompMessage outbound, String message, List<Tuple2<String, StompMessage>> nackMessages) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onBegin(WebSocketSession session, StompMessage inbound, StompMessage outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onCommit(WebSocketSession session, StompMessage inbound, StompMessage outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onAbort(WebSocketSession session, StompMessage inbound, StompMessage outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onDisconnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		return Mono.justOrEmpty(outbound);
	}

	default Mono<StompMessage> onError(WebSocketSession session, StompMessage inbound, StompMessage outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		return Mono.just(outbound);
	}

}
