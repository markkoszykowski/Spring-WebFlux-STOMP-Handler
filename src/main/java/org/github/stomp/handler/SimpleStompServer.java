package org.github.stomp.handler;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.server.StompMessage;
import org.github.stomp.server.StompServer;
import org.github.stomp.server.StompUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuple2;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
public class SimpleStompServer implements StompServer {

	public static final String SIMPLE_WEBSOCKET_PATH = "/simple";

	private static final int REALLY_LARGE_NUMBER = 7;
	private static final long DELAY_MILLIS = 300;

	public static StompMessage generateMessage(WebSocketSession session, String destination, String subscriptionId, int i) {
		return StompUtils.makeMessage(session.getId(), destination, subscriptionId, i > 0 ? String.valueOf(i) : "Watch me count!");
	}

	private final ConcurrentHashMap<String, Sinks.Many<StompMessage>> sessionCounters = new ConcurrentHashMap<>();

	@Override
	public String path() {
		return SIMPLE_WEBSOCKET_PATH;
	}

	@Override
	public Mono<List<Flux<StompMessage>>> addWebSocketSources(WebSocketSession session) {
		return Mono.just(Collections.singletonList(
				sessionCounters.compute(session.getId(), (k, v) -> Sinks.many().unicast().onBackpressureBuffer())
						.asFlux().delayElements(Duration.ofMillis(DELAY_MILLIS))
		));
	}

	@Override
	public Mono<Void> doOnEachInbound(WebSocketSession session, StompMessage inbound) {
		log.debug("Session {} -> Receiving:\n{}", session.getId(), inbound);
		return StompServer.super.doOnEachInbound(session, inbound);
	}

	@Override
	public Mono<Void> doOnEachOutbound(WebSocketSession session, StompMessage outbound) {
		log.debug("Session {} -> Sending:\n{}", session.getId(), outbound);
		return StompServer.super.doOnEachOutbound(session, outbound);
	}

	@Override
	public Mono<Void> doFinally(WebSocketSession session, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		sessionCounters.remove(session.getId());
		log.info("Closing session {}", session.getId());
		return StompServer.super.doFinally(session, messagesQueueBySubscription, messagesCache);
	}

	@Override
	public Mono<StompMessage> onStomp(WebSocketSession session, StompMessage inbound, StompMessage outbound, Version version, String host) {
		log.debug("Sweet, new connection!");
		return StompServer.super.onStomp(session, inbound, outbound, version, host);
	}

	@Override
	public Mono<StompMessage> onConnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, Version version, String host) {
		log.debug("Sweet, new connection!");
		return StompServer.super.onConnect(session, inbound, outbound, version, host);
	}

	@Override
	public Mono<StompMessage> onSubscribe(WebSocketSession session, StompMessage inbound, StompMessage outbound, String destination, String subscriptionId) {
		Sinks.Many<StompMessage> userSink = sessionCounters.get(session.getId());
		for (int i = 0; i < REALLY_LARGE_NUMBER + 1; i++) {
			userSink.tryEmitNext(generateMessage(session, destination, subscriptionId, i)).orThrow();
		}
		if (outbound != null) {
			return Mono.just(outbound);
		}
		// Testing non-default charset encodings
		Charset charset = StandardCharsets.UTF_16LE;
		String body = "You didn't want a receipt... But you get this instead:\nCongrats! You have subscribed!";
		byte[] bodyBytes = body.getBytes(charset);
		return Mono.just(StompUtils.makeMessage(session.getId(), destination, subscriptionId, Map.of("congrats", Collections.singletonList("you're subscribed!")), new MimeType(MediaType.TEXT_PLAIN, charset), bodyBytes));
	}

	@Override
	public Mono<StompMessage> onDisconnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		log.debug("Now that's a graceful disconnection!");
		return StompServer.super.onDisconnect(session, inbound, outbound, messagesQueueBySubscription, messagesCache);
	}

}
