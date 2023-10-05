package org.github.stomp.server;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.server.StompServer.AckMode;
import org.github.stomp.server.StompServer.Version;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
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

@Slf4j
final class StompHandler implements WebSocketHandler {

	final StompServer server;

	StompHandler(final StompServer server) {
		this.server = server;
	}

	static final List<Version> SUPPORTED_VERSIONS = List.of(Version.v1_2, Version.v1_1, Version.v1_0);

	static String versionsToString(final String delimiter) {
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
			Map.entry(StompCommand.STOMP, this::handleStomp),
			Map.entry(StompCommand.CONNECT, this::handleConnect),
			Map.entry(StompCommand.SEND, this::handleSend),
			Map.entry(StompCommand.SUBSCRIBE, this::handleSubscribe),
			Map.entry(StompCommand.UNSUBSCRIBE, this::handleUnsubscribe),
			Map.entry(StompCommand.ACK, this::handleAck),
			Map.entry(StompCommand.NACK, this::handleNack),
			Map.entry(StompCommand.BEGIN, this::handleBegin),
			Map.entry(StompCommand.COMMIT, this::handleCommit),
			Map.entry(StompCommand.ABORT, this::handleAbort),
			Map.entry(StompCommand.DISCONNECT, this::handleDisconnect)
	);

	Flux<StompFrame> sessionReceiver(final WebSocketSession session) {
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
	public Mono<Void> handle(final WebSocketSession session) {
		return session.send(
				this.sessionReceiver(session)
						.mergeWith(this.server.addWebSocketSources(session).flatMapMany(Flux::merge))
						.doOnNext(outbound -> this.cacheMessageForAck(session, outbound))
						.flatMap(outbound -> this.server.doOnEachOutbound(session, outbound).thenReturn(outbound))
						.map(outbound -> outbound.toWebSocketMessage(session))
		).then(Mono.defer(() -> {
			final String sessionId = session.getId();
			return this.server.doFinally(session, this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
		}));
	}

	Mono<StompFrame> handleProtocolNegotiation(final WebSocketSession session, final StompFrame inbound, final QuintFunction<WebSocketSession, StompFrame, StompFrame, Version, String, Mono<StompFrame>> callback) {
		final String versionsString = inbound.headers.getFirst(StompHeaders.ACCEPT_VERSION);
		final Version usingVersion;
		if (versionsString != null) {
			final Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = SUPPORTED_VERSIONS.stream().filter(version -> versionsSet.contains(version.version)).findFirst().orElse(null);
		} else {
			usingVersion = Version.v1_0;
		}

		if (usingVersion == null) {
			final MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
			headers.add(StompUtils.VERSION, versionsToString(","));

			return Mono.just(StompUtils.makeError(inbound, "unsupported protocol versions", headers, String.format("Supported protocol versions are %s", versionsToString(" "))
			));
		}

		final String host = inbound.headers.getFirst(StompHeaders.HOST);
		if (host == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.HOST));
		}

		final MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
		headers.add(StompUtils.VERSION, usingVersion.toString());
		headers.add(StompHeaders.SESSION, session.getId());

		return callback.apply(session, inbound, new StompFrame(StompCommand.CONNECTED, headers, null, null), usingVersion, host);
	}

	Mono<StompFrame> handleStomp(final WebSocketSession session, final StompFrame inbound) {
		return this.handleProtocolNegotiation(session, inbound, this.server::onStomp);
	}

	Mono<StompFrame> handleConnect(final WebSocketSession session, final StompFrame inbound) {
		return this.handleProtocolNegotiation(session, inbound, this.server::onConnect);
	}

	Mono<StompFrame> handleSend(final WebSocketSession session, final StompFrame inbound) {
		final String destination = inbound.headers.getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.DESTINATION));
		}
		return this.server.onSend(session, inbound, StompUtils.makeReceipt(inbound), destination);
	}

	Mono<StompFrame> handleSubscribe(final WebSocketSession session, final StompFrame inbound) {
		final String destination = inbound.headers.getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.DESTINATION));
		}

		final String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		final AckMode ackMode = AckMode.from(inbound.headers.getFirst(StompHeaders.ACK));
		if (ackMode != null && ackMode != AckMode.AUTO) {
			this.ackSubscriptionCache.computeIfAbsent(session.getId(), k -> new ConcurrentHashMap<>())
					.put(subscriptionId, Tuples.of(ackMode, new ConcurrentLinkedQueue<>()));
		}

		return this.server.onSubscribe(session, inbound, StompUtils.makeReceipt(inbound), destination, subscriptionId);
	}

	Mono<StompFrame> handleUnsubscribe(final WebSocketSession session, final StompFrame inbound) {
		final String sessionId = session.getId();

		final String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		final ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache != null) {
			final Tuple2<AckMode, ConcurrentLinkedQueue<String>> subscriptionInfo = subscriptionCache.get(subscriptionId);
			if (subscriptionInfo != null) {
				final ConcurrentHashMap<String, StompFrame> frameCache = this.ackFrameCache.get(sessionId);
				if (frameCache != null) {
					subscriptionInfo.getT2().forEach(frameCache::remove);
				}
			}
		}

		return this.server.onUnsubscribe(session, inbound, StompUtils.makeReceipt(inbound), subscriptionId);
	}

	Mono<StompFrame> handleAckOrNack(final WebSocketSession session, final StompFrame inbound, final HexFunction<WebSocketSession, StompFrame, StompFrame, String, String, List<StompFrame>, Mono<StompFrame>> callback) {
		final String sessionId = session.getId();
		final String ackId = inbound.headers.getFirst(StompHeaders.ID);
		if (ackId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		final ConcurrentHashMap<String, StompFrame> frameCache = this.ackFrameCache.get(sessionId);
		if (frameCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		final StompFrame frame = frameCache.get(ackId);
		if (frame == null) {
			return Mono.just(StompUtils.makeError(inbound, "frame info not found in cache"));
		}

		final String subscription = frame.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Sent MESSAGE without subscription");

		final ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		final Tuple2<AckMode, ConcurrentLinkedQueue<String>> subscriptionInfo = subscriptionCache.get(subscription);
		if (subscriptionInfo == null) {
			return Mono.just(StompUtils.makeError(inbound, "subscription info not found in cache"));
		}

		final List<StompFrame> ackOrNackMessages = new LinkedList<>();
		if (subscriptionInfo.getT1() == AckMode.CLIENT) {
			synchronized (subscriptionInfo.getT2()) {
				if (subscriptionInfo.getT2().contains(ackId)) {
					String a;
					do {
						a = subscriptionInfo.getT2().poll();
						if (a == null) {
							break;
						}
						final StompFrame removed = frameCache.remove(ackId);
						if (removed != null) {
							ackOrNackMessages.add(removed);
						}
					} while (!a.equals(ackId));
				}
			}
		} else if (subscriptionInfo.getT1() == AckMode.CLIENT_INDIVIDUAL) {
			subscriptionInfo.getT2().remove(ackId);
			final StompFrame removed = frameCache.remove(ackId);
			if (removed != null) {
				ackOrNackMessages.add(removed);
			}
		}

		return callback.apply(session, inbound, StompUtils.makeReceipt(inbound), subscription, ackId, ackOrNackMessages);
	}

	Mono<StompFrame> handleAck(final WebSocketSession session, final StompFrame inbound) {
		return this.handleAckOrNack(session, inbound, this.server::onAck);
	}

	Mono<StompFrame> handleNack(final WebSocketSession session, final StompFrame inbound) {
		return this.handleAckOrNack(session, inbound, this.server::onNack);
	}

	Mono<StompFrame> handleTransactionFrame(final WebSocketSession session, final StompFrame inbound, final QuadFunction<WebSocketSession, StompFrame, StompFrame, String, Mono<StompFrame>> callback) {
		final String transaction = inbound.headers.getFirst(StompUtils.TRANSACTION);
		if (transaction == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompUtils.TRANSACTION));
		}
		return callback.apply(session, inbound, StompUtils.makeReceipt(inbound), transaction);
	}

	Mono<StompFrame> handleBegin(final WebSocketSession session, final StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, this.server::onBegin);
	}

	Mono<StompFrame> handleCommit(final WebSocketSession session, final StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, this.server::onCommit);
	}

	Mono<StompFrame> handleAbort(final WebSocketSession session, final StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, this.server::onAbort);
	}

	Mono<StompFrame> handleDisconnect(final WebSocketSession session, final StompFrame inbound) {
		final String sessionId = session.getId();
		return this.server.onDisconnect(session, inbound, StompUtils.makeReceipt(inbound), this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
	}

	Mono<Void> handleError(final WebSocketSession session, final StompFrame inbound, final StompFrame outbound) {
		if (outbound.command != StompCommand.ERROR) {
			return Mono.empty();
		}
		final String sessionId = session.getId();
		return this.server.onError(session, inbound, outbound, this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
	}

	void cacheMessageForAck(final WebSocketSession session, final StompFrame outbound) {
		if (outbound.command != StompCommand.MESSAGE) {
			return;
		}

		final String sessionId = session.getId();
		final String subscription = outbound.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Trying to send MESSAGE without subscription");

		final ConcurrentHashMap<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache == null) {
			return;
		}

		final Tuple2<AckMode, ConcurrentLinkedQueue<String>> subscriptionInfo = subscriptionCache.get(subscription);
		if (subscriptionInfo == null || subscriptionInfo.getT1() == AckMode.AUTO) {
			return;
		}

		final String ackId = UUID.randomUUID().toString();
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
