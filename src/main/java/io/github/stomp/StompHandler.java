package io.github.stomp;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Slf4j
final class StompHandler implements WebSocketHandler {

	final StompServer server;

	StompHandler(final StompServer server) {
		this.server = server;
	}

	static final List<StompServer.Version> SUPPORTED_VERSIONS = List.of(StompServer.Version.v1_2, StompServer.Version.v1_1, StompServer.Version.v1_0);

	static String versionsToString(final String delimiter) {
		return SUPPORTED_VERSIONS.stream()
				.sorted(Comparator.comparing(StompServer.Version::version))
				.map(StompServer.Version::toString)
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
	final Map<String, Map<String, Tuple2<StompServer.AckMode, Queue<String>>>> ackSubscriptionCache = new ConcurrentHashMap<>();
	// SessionId -> Ack -> StompFrame
	final Map<String, Map<String, StompFrame>> ackFrameCache = new ConcurrentHashMap<>();

	TriFunction<StompHandler, WebSocketSession, StompFrame, Mono<StompFrame>> handler(final StompCommand command) {
		return switch (command) {
			case StompCommand.STOMP -> StompHandler::handleStomp;
			case StompCommand.CONNECT -> StompHandler::handleConnect;
			case StompCommand.SEND -> StompHandler::handleSend;
			case StompCommand.SUBSCRIBE -> StompHandler::handleSubscribe;
			case StompCommand.UNSUBSCRIBE -> StompHandler::handleUnsubscribe;
			case StompCommand.ACK -> StompHandler::handleAck;
			case StompCommand.NACK -> StompHandler::handleNack;
			case StompCommand.BEGIN -> StompHandler::handleBegin;
			case StompCommand.COMMIT -> StompHandler::handleCommit;
			case StompCommand.ABORT -> StompHandler::handleAbort;
			case StompCommand.DISCONNECT -> StompHandler::handleDisconnect;
			case CONNECTED, RECEIPT, MESSAGE, ERROR -> throw new UnsupportedOperationException("'command' is server STOMP command");
		};
	}

	Flux<StompFrame> sessionReceiver(final WebSocketSession session) {
		return session.receive()
				.map(StompFrame::from)
				.flatMap(inbound -> this.server.doOnEachInbound(session, inbound).thenReturn(inbound))
				.takeUntil(inbound -> inbound.command == StompCommand.DISCONNECT)
				.flatMap(inbound -> this.handler(inbound.command)
						.apply(this, session, inbound)
						.flatMap(outbound -> this.handleError(session, inbound, outbound).thenReturn(outbound))
				)
				.takeUntil(outbound -> outbound.command == StompCommand.ERROR);
	}

	@NonNull
	@Override
	public Mono<Void> handle(@NonNull final WebSocketSession session) {
		final Flux<StompFrame> receiver = this.sessionReceiver(session);
		return session.send(
				receiver.mergeWith(this.server.addWebSocketSources(session).flatMapMany(Flux::merge))
						.takeUntilOther(receiver.ignoreElements())
						.doOnNext(outbound -> this.cacheMessageForAck(session, outbound))
						.flatMap(outbound -> this.server.doOnEachOutbound(session, outbound).thenReturn(outbound))
						.map(outbound -> outbound.toWebSocketMessage(session))
						.doOnError(ex -> log.error("Error during WebSocket handling: {}", ex.getMessage()))
		).then(Mono.defer(() -> {
			final String sessionId = session.getId();
			return this.server.doFinally(session, this.ackSubscriptionCache.remove(sessionId), this.ackFrameCache.remove(sessionId));
		}));
	}

	Mono<StompFrame> handleProtocolNegotiation(final WebSocketSession session, final StompFrame inbound, final HexFunction<StompServer, WebSocketSession, StompFrame, StompFrame, StompServer.Version, String, Mono<StompFrame>> callback) {
		final String versionsString = inbound.headers.getFirst(StompHeaders.ACCEPT_VERSION);
		final StompServer.Version usingVersion;
		if (versionsString != null) {
			final Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = SUPPORTED_VERSIONS.stream().filter(version -> versionsSet.contains(version.version)).findFirst().orElse(null);
		} else {
			usingVersion = StompServer.Version.v1_0;
		}

		if (usingVersion == null) {
			final MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
			headers.add(StompUtils.VERSION, versionsToString(","));

			return Mono.just(StompUtils.makeError(inbound, "unsupported protocol versions", headers, String.format("Supported protocol versions are %s", versionsToString(" "))));
		}

		final String host = inbound.headers.getFirst(StompHeaders.HOST);
		if (host == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.HOST));
		}

		final MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
		headers.add(StompUtils.VERSION, usingVersion.toString());
		headers.add(StompHeaders.SESSION, session.getId());

		return callback.apply(this.server, session, inbound, new StompFrame(StompCommand.CONNECTED, headers, null, null), usingVersion, host);
	}

	Mono<StompFrame> handleStomp(final WebSocketSession session, final StompFrame inbound) {
		return this.handleProtocolNegotiation(session, inbound, StompServer::onStomp);
	}

	Mono<StompFrame> handleConnect(final WebSocketSession session, final StompFrame inbound) {
		return this.handleProtocolNegotiation(session, inbound, StompServer::onConnect);
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

		final StompServer.AckMode ackMode = StompServer.AckMode.from(inbound.headers.getFirst(StompHeaders.ACK));
		if (ackMode != null && ackMode != StompServer.AckMode.AUTO) {
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

		final Map<String, Tuple2<StompServer.AckMode, Queue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache != null) {
			final Tuple2<StompServer.AckMode, Queue<String>> subscriptionInfo = subscriptionCache.get(subscriptionId);
			if (subscriptionInfo != null) {
				final Map<String, StompFrame> frameCache = this.ackFrameCache.get(sessionId);
				if (frameCache != null) {
					subscriptionInfo.getT2().forEach(frameCache::remove);
				}
			}
		}

		return this.server.onUnsubscribe(session, inbound, StompUtils.makeReceipt(inbound), subscriptionId);
	}

	Mono<StompFrame> handleAckOrNack(final WebSocketSession session, final StompFrame inbound, final HeptFunction<StompServer, WebSocketSession, StompFrame, StompFrame, String, String, List<StompFrame>, Mono<StompFrame>> callback) {
		final String sessionId = session.getId();
		final String ackId = inbound.headers.getFirst(StompHeaders.ID);
		if (ackId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		final Map<String, StompFrame> frameCache = this.ackFrameCache.get(sessionId);
		if (frameCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		final StompFrame frame = frameCache.get(ackId);
		if (frame == null) {
			return Mono.just(StompUtils.makeError(inbound, "frame info not found in cache"));
		}

		final String subscription = frame.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Sent MESSAGE without subscription");

		final Map<String, Tuple2<StompServer.AckMode, Queue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		final Tuple2<StompServer.AckMode, Queue<String>> subscriptionInfo = subscriptionCache.get(subscription);
		if (subscriptionInfo == null) {
			return Mono.just(StompUtils.makeError(inbound, "subscription info not found in cache"));
		}

		final List<StompFrame> ackOrNackMessages = new LinkedList<>();
		if (subscriptionInfo.getT1() == StompServer.AckMode.CLIENT) {
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
		} else if (subscriptionInfo.getT1() == StompServer.AckMode.CLIENT_INDIVIDUAL) {
			subscriptionInfo.getT2().remove(ackId);
			final StompFrame removed = frameCache.remove(ackId);
			if (removed != null) {
				ackOrNackMessages.add(removed);
			}
		}

		return callback.apply(this.server, session, inbound, StompUtils.makeReceipt(inbound), subscription, ackId, ackOrNackMessages);
	}

	Mono<StompFrame> handleAck(final WebSocketSession session, final StompFrame inbound) {
		return this.handleAckOrNack(session, inbound, StompServer::onAck);
	}

	Mono<StompFrame> handleNack(final WebSocketSession session, final StompFrame inbound) {
		return this.handleAckOrNack(session, inbound, StompServer::onNack);
	}

	Mono<StompFrame> handleTransactionFrame(final WebSocketSession session, final StompFrame inbound, final QuintFunction<StompServer, WebSocketSession, StompFrame, StompFrame, String, Mono<StompFrame>> callback) {
		final String transaction = inbound.headers.getFirst(StompUtils.TRANSACTION);
		if (transaction == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompUtils.TRANSACTION));
		}
		return callback.apply(this.server, session, inbound, StompUtils.makeReceipt(inbound), transaction);
	}

	Mono<StompFrame> handleBegin(final WebSocketSession session, final StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, StompServer::onBegin);
	}

	Mono<StompFrame> handleCommit(final WebSocketSession session, final StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, StompServer::onCommit);
	}

	Mono<StompFrame> handleAbort(final WebSocketSession session, final StompFrame inbound) {
		return this.handleTransactionFrame(session, inbound, StompServer::onAbort);
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

		final Map<String, Tuple2<StompServer.AckMode, Queue<String>>> subscriptionCache = this.ackSubscriptionCache.get(sessionId);
		if (subscriptionCache == null) {
			return;
		}

		final Tuple2<StompServer.AckMode, Queue<String>> subscriptionInfo = subscriptionCache.get(subscription);
		if (subscriptionInfo == null || subscriptionInfo.getT1() == StompServer.AckMode.AUTO) {
			return;
		}

		final String ackId = UUID.randomUUID().toString();
		subscriptionInfo.getT2().add(ackId);
		this.ackFrameCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(ackId, outbound);

		outbound.headers.add(StompHeaders.ACK, ackId);
	}

	@FunctionalInterface
	interface TriFunction<T, U, V, R> {
		R apply(T t, U u, V v);
	}

	@FunctionalInterface
	interface QuintFunction<T, U, V, W, X, R> {
		R apply(T t, U u, V v, W w, X x);
	}

	@FunctionalInterface
	interface HexFunction<T, U, V, W, X, Y, R> {
		R apply(T t, U u, V v, W w, X x, Y y);
	}

	@FunctionalInterface
	interface HeptFunction<T, U, V, W, X, Y, Z, R> {
		R apply(T t, U u, V v, W w, X x, Y y, Z z);
	}

}
