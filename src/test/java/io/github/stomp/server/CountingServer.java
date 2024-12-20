package io.github.stomp.server;

import io.github.stomp.StompFrame;
import io.github.stomp.StompServer;
import io.github.stomp.StompUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CountingServer implements StompServer {

	private static final Scheduler COUNTING_SCHEDULER = Schedulers.boundedElastic();

	public static final String COUNTING_WEBSOCKET_PATH = "/count";

	private static final long DELAY_MILLIS = 500L;

	private final Map<String, Map<String, Disposable>> subscriptions = new ConcurrentHashMap<>();
	private final Map<String, Sinks.Many<StompFrame>> sinks = new ConcurrentHashMap<>();

	public static StompFrame generateCountMessage(final String destination, final String subscriptionId, final long i) {
		return StompUtils.makeMessage(destination, subscriptionId, i > 0 ? String.valueOf(i) : "Starting count");
	}

	@Override
	public String path() {
		return COUNTING_WEBSOCKET_PATH;
	}

	@Override
	public Mono<List<Flux<StompFrame>>> addWebSocketSources(final WebSocketSession session) {
		return Mono.just(
				Collections.singletonList(
						this.sinks.computeIfAbsent(session.getId(), k -> Sinks.many().unicast().onBackpressureBuffer()).asFlux()
				)
		);
	}

	@Override
	public Mono<Void> doFinally(final WebSocketSession session, final Map<String, Tuple2<AckMode, Queue<String>>> subscriptionCache, final Map<String, StompFrame> frameCache) {
		final String sessionId = session.getId();
		final Map<String, Disposable> subscriptions = this.subscriptions.remove(sessionId);
		if (subscriptions != null) {
			subscriptions.forEach((k, v) -> {
				if (v != null) {
					v.dispose();
				}
			});
		}
		this.sinks.remove(sessionId);
		return StompServer.super.doFinally(session, subscriptionCache, frameCache);
	}

	@Override
	public Mono<StompFrame> onSubscribe(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound, final String destination, final String subscriptionId) {
		this.subscriptions.computeIfAbsent(session.getId(), k -> new ConcurrentHashMap<>())
				.put(
						subscriptionId,
						Flux.interval(Duration.ofMillis(DELAY_MILLIS))
								.doOnNext(i -> {
									final Sinks.Many<StompFrame> sink = this.sinks.get(session.getId());
									if (sink != null) {
										sink.tryEmitNext(generateCountMessage(destination, subscriptionId, i));
									}
								})
								.subscribeOn(COUNTING_SCHEDULER)
								.subscribe()
				);

		return StompServer.super.onSubscribe(session, inbound, outbound, destination, subscriptionId);
	}

	@Override
	public Mono<StompFrame> onUnsubscribe(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound, final String subscriptionId) {
		final Map<String, Disposable> sessionSubscriptions = this.subscriptions.get(session.getId());
		if (sessionSubscriptions != null) {
			final Disposable disposable = sessionSubscriptions.remove(subscriptionId);
			if (disposable != null) {
				disposable.dispose();
			}
		}
		return StompServer.super.onUnsubscribe(session, inbound, outbound, subscriptionId);
	}

}
