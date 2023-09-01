package org.github.stomp.server;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.server.StompServer.AckMode;
import org.github.stomp.server.StompServer.Version;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.Map.entry;

@Slf4j
final class StompHandler implements WebSocketHandler {

	final StompServer server;

	StompHandler(StompServer server) {
		this.server = server;
	}

	static final List<Version> SUPPORTED_VERSIONS = List.of(Version.v1_2, Version.v1_1, Version.v1_0);

	static String versionsToString(String delimiter) {
		return SUPPORTED_VERSIONS.stream()
				.sorted(Comparator.comparing(Version::version))
				.map(Version::toString)
				.collect(Collectors.joining(delimiter));
	}

	@NonNull
	@Override
	public List<String> getSubProtocols() {
		return SUPPORTED_VERSIONS.stream()
				.map(StompServer.Version::toString)
				.map(v -> String.format("STOMP %s", v))
				.collect(Collectors.toList());
	}

	// Caches
	// SessionId -> Subscription -> <ACK Mode, [Ack, ...]>
	final ConcurrentHashMap<String, ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>>> ackSubscriptionCache = new ConcurrentHashMap<>();
	// SessionId -> Ack -> StompFrame
	final ConcurrentHashMap<String, ConcurrentHashMap<String, StompFrame>> ackFrameCache = new ConcurrentHashMap<>();

	final Map<StompCommand, BiFunction<WebSocketSession, StompFrame, Mono<StompFrame>>> handler = Map.ofEntries(
			entry(StompCommand.STOMP, this::handleStomp),
			entry(StompCommand.CONNECT, this::handleConnect),
			entry(StompCommand.SEND, this::handleSend),
			entry(StompCommand.SUBSCRIBE, this::handleSubscribe),
			entry(StompCommand.UNSUBSCRIBE, this::handleUnsubscribe),
			entry(StompCommand.ACK, this::handleAck),
			entry(StompCommand.NACK, this::handleNack),
			entry(StompCommand.BEGIN, this::handleBegin),
			entry(StompCommand.COMMIT, this::handleCommit),
			entry(StompCommand.ABORT, this::handleAbort),
			entry(StompCommand.DISCONNECT, this::handleDisconnect)
	);

	Flux<StompFrame> sessionReceiver(WebSocketSession session) {
		return session.receive()
				.map(StompFrame::from)
				.flatMap(inbound -> this.server.doOnEachInbound(session, inbound).thenReturn(inbound))
				.takeUntil(inbound -> inbound.command == StompCommand.DISCONNECT)
				.flatMap(inbound ->
						this.handler.get(inbound.command)
								.apply(session, inbound)
								.flatMap(outbound -> this.handleError(session, inbound, outbound).thenReturn(outbound))
				)
				.takeUntil(outbound -> outbound.command == StompCommand.ERROR)
				.doOnError(ex -> log.error("Error during WebSocket handling: {}", ex.getMessage()))
				.doFinally(signalType -> session.close().subscribeOn(Schedulers.immediate()).subscribe());
	}

	@NonNull
	@Override
	public Mono<Void> handle(WebSocketSession session) {
		return session.send(
				this.sessionReceiver(session)
						.mergeWith(this.server.addWebSocketSources(session).flatMapMany(Flux::merge))
						.doOnNext(outbound -> this.cacheMessageForAck(session, outbound))
						.flatMap(outbound -> this.server.doOnEachOutbound(session, outbound).thenReturn(outbound))
						.map(outbound -> outbound.toWebSocketMessage(session))
		).then(Mono.defer(() -> {
			String sessionId = session.getId();
			return this.server.doFinally(session, this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
		}));
	}

	Mono<StompFrame> handleProtocolNegotiation(WebSocketSession session, StompFrame inbound, QuintFunction<WebSocketSession, StompFrame, StompFrame, Version, String, Mono<StompFrame>> onFunction) {
		String versionsString = inbound.headers.getFirst(StompHeaders.ACCEPT_VERSION);
		Version usingVersion;
		if (versionsString != null) {
			Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = SUPPORTED_VERSIONS.stream().filter(version -> versionsSet.contains(version.version)).findFirst().orElse(null);
		} else {
			usingVersion = Version.v1_0;
		}

		if (usingVersion == null) {
			return Mono.just(StompUtils.makeError(inbound, "unsupported protocol versions",
					new HashMap<>() {{
						put(StompUtils.VERSION, new ArrayList<>(1) {{
							add(versionsToString(","));
						}});
					}}, String.format("Supported protocol versions are %s", versionsToString(" "))
			));
		}

		String host = inbound.headers.getFirst(StompHeaders.HOST);
		if (host == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.HOST));
		}

		return onFunction.apply(session, inbound, new StompFrame(StompCommand.CONNECTED,
				CollectionUtils.toMultiValueMap(new HashMap<>() {{
					put(StompUtils.VERSION, new ArrayList<>(1) {{
						add(usingVersion.toString());
					}});
					put(StompHeaders.SESSION, new ArrayList<>(1) {{
						add(session.getId());
					}});
				}}), null, null), usingVersion, host);
	}

	Mono<StompFrame> handleStomp(WebSocketSession session, StompFrame inbound) {
		return this.handleProtocolNegotiation(session, inbound, this.server::onStomp);
	}

	Mono<StompFrame> handleConnect(WebSocketSession session, StompFrame inbound) {
		return this.handleProtocolNegotiation(session, inbound, this.server::onConnect);
	}

	Mono<StompFrame> handleSend(WebSocketSession session, StompFrame inbound) {
		String destination = inbound.headers.getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.DESTINATION));
		}
		return this.server.onSend(session, inbound, StompUtils.makeReceipt(inbound), destination);
	}

	Mono<StompFrame> handleSubscribe(WebSocketSession session, StompFrame inbound) {
		String destination = inbound.headers.getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.DESTINATION));
		}

		String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		AckMode ackMode = AckMode.from(inbound.headers.getFirst(StompHeaders.ACK));
		if (ackMode != null && ackMode != AckMode.AUTO) {
			this.ackSubscriptionCache.computeIfAbsent(session.getId(), k -> new ConcurrentHashMap<>())
					.put(subscriptionId, Tuples.of(ackMode, new ConcurrentLinkedQueue<>()));
		}

		return this.server.onSubscribe(session, inbound, StompUtils.makeReceipt(inbound), destination, subscriptionId);
	}

	Mono<StompFrame> handleUnsubscribe(WebSocketSession session, StompFrame inbound) {
		String sessionId = session.getId();

		String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache != null) {
			Tuple2<AckMode, ConcurrentLinkedQueue<String>> subscriptionInfo = subscriptionCache.get(subscriptionId);
			if (subscriptionInfo != null) {
				ConcurrentHashMap<String, StompFrame> frameCache = this.ackFrameCache.get(sessionId);
				if (frameCache != null) {
					subscriptionInfo.getT2().forEach(frameCache::remove);
				}
			}
		}

		return this.server.onUnsubscribe(session, inbound, StompUtils.makeReceipt(inbound), subscriptionId);
	}

	Mono<StompFrame> handleAckOrNack(WebSocketSession session, StompFrame inbound, HexFunction<WebSocketSession, StompFrame, StompFrame, String, String, List<StompFrame>, Mono<StompFrame>> onFunction) {
		String sessionId = session.getId();
		String ackId = inbound.headers.getFirst(StompHeaders.ID);
		if (ackId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		ConcurrentHashMap<String, StompFrame> frameCache = this.ackFrameCache.get(sessionId);
		if (frameCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		StompFrame frame = frameCache.get(ackId);
		if (frame == null) {
			return Mono.just(StompUtils.makeError(inbound, "frame info not found in cache"));
		}

		String subscription = frame.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Sent MESSAGE without subscription");

		ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		Tuple2<AckMode, ConcurrentLinkedQueue<String>> subscriptionInfo = subscriptionCache.get(subscription);
		if (subscriptionInfo == null) {
			return Mono.just(StompUtils.makeError(inbound, "subscription info not found in cache"));
		}

		List<StompFrame> ackOrNackMessages = new LinkedList<>();
		if (subscriptionInfo.getT1() == AckMode.CLIENT) {
			synchronized (subscriptionInfo.getT2()) {
				if (subscriptionInfo.getT2().contains(ackId)) {
					String a;
					do {
						a = subscriptionInfo.getT2().poll();
						if (a == null) break;
						StompFrame removed = frameCache.remove(ackId);
						if (removed != null) {
							ackOrNackMessages.add(removed);
						}
					} while (!a.equals(ackId));
				}
			}
		} else if (subscriptionInfo.getT1() == AckMode.CLIENT_INDIVIDUAL) {
			subscriptionInfo.getT2().remove(ackId);
			StompFrame removed = frameCache.remove(ackId);
			if (removed != null) {
				ackOrNackMessages.add(removed);
			}
		}

		return onFunction.apply(session, inbound, StompUtils.makeReceipt(inbound), subscription, ackId, ackOrNackMessages);
	}

	Mono<StompFrame> handleAck(WebSocketSession session, StompFrame inbound) {
		return this.handleAckOrNack(session, inbound, this.server::onAck);
	}

	Mono<StompFrame> handleNack(WebSocketSession session, StompFrame inbound) {
		return this.handleAckOrNack(session, inbound, this.server::onNack);
	}

	Mono<StompFrame> handleTransactionFrame(WebSocketSession session, StompFrame inbound, QuadFunction<WebSocketSession, StompFrame, StompFrame, String, Mono<StompFrame>> onFunction) {
		String transaction = inbound.headers.getFirst(StompUtils.TRANSACTION);
		if (transaction == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompUtils.TRANSACTION));
		}
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(inbound), transaction);
	}

	Mono<StompFrame> handleBegin(WebSocketSession session, StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, this.server::onBegin);
	}

	Mono<StompFrame> handleCommit(WebSocketSession session, StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, this.server::onCommit);
	}

	Mono<StompFrame> handleAbort(WebSocketSession session, StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, this.server::onAbort);
	}

	Mono<StompFrame> handleDisconnect(WebSocketSession session, StompFrame inbound) {
		String sessionId = session.getId();
		return this.server.onDisconnect(session, inbound, StompUtils.makeReceipt(inbound), this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
	}

	Mono<Void> handleError(WebSocketSession session, StompFrame inbound, StompFrame outbound) {
		if (outbound.command != StompCommand.ERROR) {
			return Mono.empty();
		}
		String sessionId = session.getId();
		return this.server.onError(session, inbound, outbound, this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
	}

	void cacheMessageForAck(WebSocketSession session, StompFrame outbound) {
		if (outbound.command != StompCommand.MESSAGE) {
			return;
		}

		String sessionId = session.getId();
		String subscription = outbound.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Trying to send MESSAGE without subscription");

		ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache == null) {
			return;
		}

		Tuple2<AckMode, ConcurrentLinkedQueue<String>> subscriptionInfo = subscriptionCache.get(subscription);
		if (subscriptionInfo == null || subscriptionInfo.getT1() == AckMode.AUTO) {
			return;
		}

		String ackId = UUID.randomUUID().toString();
		subscriptionInfo.getT2().add(ackId);
		this.ackFrameCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(ackId, outbound);

		outbound.headers.add(StompHeaders.ACK, ackId);
	}

	@FunctionalInterface
	interface QuadFunction<T, U, V, W, R> {
		R apply(T t, U u, V v, W w);
	}

	@FunctionalInterface
	interface QuintFunction<T, U, V, W, X, R> {
		R apply(T t, U u, V v, W w, X x);
	}

	@FunctionalInterface
	interface HexFunction<T, U, V, W, X, Y, R> {
		R apply(T t, U u, V v, W w, X x, Y y);
	}

}
