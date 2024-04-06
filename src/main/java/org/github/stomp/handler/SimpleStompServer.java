package org.github.stomp.handler;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.server.StompFrame;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SimpleStompServer implements StompServer {

	public static final String SIMPLE_WEBSOCKET_PATH = "/simple";

	private static final int REALLY_LARGE_NUMBER = 7;
	private static final long DELAY_MILLIS = 300L;

	public static StompFrame generateMessage(final String destination, final String subscriptionId, final int i) {
		return StompUtils.makeMessage(destination, subscriptionId, i > 0 ? String.valueOf(i) : "Watch me count!");
	}

	private final ConcurrentHashMap<String, Sinks.Many<StompFrame>> sessionCounters = new ConcurrentHashMap<>();

	@Override
	public String path() {
		return SIMPLE_WEBSOCKET_PATH;
	}

	@Override
	public Mono<List<Flux<StompFrame>>> addWebSocketSources(final WebSocketSession session) {
		return Mono.just(Collections.singletonList(
				this.sessionCounters.compute(session.getId(), (k, v) -> Sinks.many().unicast().onBackpressureBuffer())
						.asFlux().delayElements(Duration.ofMillis(DELAY_MILLIS))
		));
	}

	@Override
	public Mono<Void> doOnEachInbound(final WebSocketSession session, final StompFrame inbound) {
		log.debug("Session {} -> Receiving:\n{}", session.getId(), inbound);
		return StompServer.super.doOnEachInbound(session, inbound);
	}

	@Override
	public Mono<Void> doOnEachOutbound(final WebSocketSession session, final StompFrame outbound) {
		log.debug("Session {} -> Sending:\n{}", session.getId(), outbound);
		return StompServer.super.doOnEachOutbound(session, outbound);
	}

	@Override
	public Mono<Void> doFinally(final WebSocketSession session, final Map<String, Tuple2<AckMode, Queue<String>>> subscriptionCache, final Map<String, StompFrame> frameCache) {
		this.sessionCounters.remove(session.getId());
		log.info("Closing session {}", session.getId());
		return StompServer.super.doFinally(session, subscriptionCache, frameCache);
	}

	@Override
	public Mono<StompFrame> onStomp(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound, final Version version, final String host) {
		log.debug("Sweet, new connection!");
		return StompServer.super.onStomp(session, inbound, outbound, version, host);
	}

	@Override
	public Mono<StompFrame> onConnect(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound, final Version version, final String host) {
		log.debug("Sweet, new connection!");
		return StompServer.super.onConnect(session, inbound, outbound, version, host);
	}

	@Override
	public Mono<StompFrame> onSubscribe(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound, final String destination, final String subscriptionId) {
		final Sinks.Many<StompFrame> userSink = this.sessionCounters.get(session.getId());
		for (int i = 0; i < REALLY_LARGE_NUMBER + 1; i++) {
			userSink.tryEmitNext(generateMessage(destination, subscriptionId, i)).orThrow();
		}
		if (outbound != null) {
			return Mono.just(outbound);
		}
		// Testing non-default charset encodings
		final Charset charset = StandardCharsets.UTF_16LE;
		final String body = "You didn't want a receipt... But you get this instead:\nCongrats! You have subscribed!";
		return Mono.just(StompUtils.makeMessage(destination, subscriptionId, Map.of(
				"congrats", Collections.singletonList("you're subscribed!")
		), new MimeType(MediaType.TEXT_PLAIN, charset), body.getBytes(charset)));
	}

	@Override
	public Mono<StompFrame> onDisconnect(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound, final Map<String, Tuple2<AckMode, Queue<String>>> subscriptionCache, final Map<String, StompFrame> frameCache) {
		log.debug("Now that's a graceful disconnection!");
		return StompServer.super.onDisconnect(session, inbound, outbound, subscriptionCache, frameCache);
	}

}
