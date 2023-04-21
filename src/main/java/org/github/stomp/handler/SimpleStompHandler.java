package org.github.stomp.handler;

import org.github.stomp.data.StompMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Component
public class SimpleStompHandler extends AbstractStompHandler {

	public static final String WEBSOCKET_PATH = "/simple";

	private static final int REALLY_LARGE_NUMBER = 7;
	private static final long DELAY_MILLIS = 100;

	public static StompMessage generateMessage(WebSocketSession session, Tuple3<String, String, Integer> messageInfo) {
		String destination = messageInfo.getT1();
		String subscription = messageInfo.getT2();
		int i = messageInfo.getT3();
		return makeMessage(session.getId(), destination, subscription, i > 0 ? String.valueOf(i) : "Watch me count!");
	}

	private final Sinks.Many<Tuple3<String, String, Integer>> counterTrigger = Sinks.many().multicast().onBackpressureBuffer();

	@Override
	public Mono<List<Flux<StompMessage>>> addWebSocketSources(WebSocketSession session) {
		return Mono.just(Collections.singletonList(
				counterTrigger.asFlux().delayElements(Duration.ofMillis(DELAY_MILLIS)).map(i -> generateMessage(session, i))
		));
	}

	@Override
	public void doOnEach(WebSocketSession session, StompMessage outbound) {
		log.debug("Session {} -> Sending:\n{}", session.getId(), outbound.toString());
	}

	@Override
	public void doFinally(WebSocketSession session, SignalType signal, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		log.info("Closing session {}", session.getId());
	}

	@Override
	public Mono<StompMessage> onStomp(WebSocketSession session, StompMessage inbound, StompMessage outbound, String version) {
		return Mono.just(outbound.mutate().bodyCharset(StompMessage.DEFAULT_CHARSET).body("Woo! Connection!".getBytes(StompMessage.DEFAULT_CHARSET)).build());
	}

	@Override
	public Mono<StompMessage> onConnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, String version) {
		return Mono.just(outbound.mutate().bodyCharset(StompMessage.DEFAULT_CHARSET).body("Woo! Connection!".getBytes(StompMessage.DEFAULT_CHARSET)).build());
	}

	@Override
	public Mono<StompMessage> onSubscribe(WebSocketSession session, StompMessage inbound, StompMessage outbound, String destination, String subscriptionId) {
		for (int i = 0; i < REALLY_LARGE_NUMBER + 1; i++) {
			counterTrigger.tryEmitNext(Tuples.of(destination, subscriptionId, i));
		}
		if (outbound != null) {
			return Mono.just(outbound);
		}
		Charset charset = StandardCharsets.UTF_16LE;
		String body = "You didn't want a receipt... But you get this instead:\nCongrats! You have subscribed!";
		byte[] bodyBytes = body.getBytes(charset);
		return Mono.just(makeMessage(session.getId(), destination, subscriptionId, Map.of("congrats", Collections.singletonList("you're subscribed!")), new MimeType(MediaType.TEXT_PLAIN, charset), bodyBytes));
	}

}
