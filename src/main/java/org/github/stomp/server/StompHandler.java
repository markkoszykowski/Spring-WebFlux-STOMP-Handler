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
	// SessionId -> Subscription -> ACK Mode
	final ConcurrentHashMap<String, ConcurrentHashMap<String, AckMode>> ackModeCache = new ConcurrentHashMap<>();
	// SessionId -> Ack -> StompFrame
	final ConcurrentHashMap<String, ConcurrentHashMap<String, StompFrame>> ackMessageCache = new ConcurrentHashMap<>();
	// SessionId -> Subscription -> [Ack, ...]
	final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>> ackSubscriptionCache = new ConcurrentHashMap<>();

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
				.flatMap(message -> this.server.doOnEachInbound(session, message).then(Mono.just(message)))
				.takeUntil(message -> message.command == StompCommand.DISCONNECT)
				.flatMap(inbound -> this.handler.get(inbound.command).apply(session, inbound)
						.flatMap(outbound -> outbound.error() ? handleError(session, inbound, outbound) : Mono.just(outbound)))
				.takeUntil(StompFrame::error)
				.doOnError(ex -> log.error("Error during WebSocket handling: {}", ex.getMessage()))
				.doFinally(signalType -> session.close().subscribeOn(Schedulers.immediate()).subscribe());
	}

	@NonNull
	@Override
	public Mono<Void> handle(WebSocketSession session) {
		return session.send(this.server.addWebSocketSources(session)
				.flatMapMany(Flux::merge)
				.mergeWith(sessionReceiver(session))
				.switchIfEmpty(sessionReceiver(session))
				.doOnNext(outbound -> cacheMessageForAck(session, outbound))
				.flatMap(message -> this.server.doOnEachOutbound(session, message).then(Mono.just(message)))
				.map(message -> message.toWebSocketMessage(session))
		).then(Mono.defer(() -> {
			Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, StompFrame>> sessionCaches = handleDisconnect(session);
			return this.server.doFinally(session, sessionCaches.one, sessionCaches.two);
		}));
	}

	Mono<StompFrame> handleProtocolNegotiation(WebSocketSession session, StompFrame inbound, QuintFunction<WebSocketSession, StompFrame, StompFrame, Version, String, Mono<StompFrame>> onFunction) {
		String versionsString = inbound.headers.getFirst(StompHeaders.ACCEPT_VERSION);
		Version usingVersion;
		if (versionsString != null) {
			Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = SUPPORTED_VERSIONS.stream().filter(version -> versionsSet.contains(version.toString())).findFirst().orElse(null);
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
		return handleProtocolNegotiation(session, inbound, this.server::onStomp);
	}

	Mono<StompFrame> handleConnect(WebSocketSession session, StompFrame inbound) {
		return handleProtocolNegotiation(session, inbound, this.server::onConnect);
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
			this.ackModeCache.computeIfAbsent(session.getId(), k -> new ConcurrentHashMap<>())
					.put(subscriptionId, ackMode);
		}

		return this.server.onSubscribe(session, inbound, StompUtils.makeReceipt(inbound), destination, subscriptionId);
	}

	Mono<StompFrame> handleUnsubscribe(WebSocketSession session, StompFrame inbound) {
		String sessionId = session.getId();

		String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		Optional.ofNullable(this.ackModeCache.get(sessionId)).ifPresent(map -> map.remove(subscriptionId));

		ConcurrentLinkedQueue<String> messageQueueCache = Optional.ofNullable(this.ackSubscriptionCache.get(sessionId))
				.map(map -> map.remove(subscriptionId))
				.orElse(null);
		if (messageQueueCache != null) {
			ConcurrentHashMap<String, StompFrame> messagesCache = this.ackMessageCache.get(sessionId);
			if (messagesCache != null) {
				messageQueueCache.forEach(messagesCache::remove);
			}
		}
		return this.server.onUnsubscribe(session, inbound, StompUtils.makeReceipt(inbound), subscriptionId);
	}

	Mono<StompFrame> handleAckOrNack(WebSocketSession session, StompFrame inbound,
									 QuadConsumer<AckMode, ConcurrentHashMap<String, StompFrame>, List<StompFrame>, String> removeMessage,
									 HexFunction<WebSocketSession, StompFrame, StompFrame, String, String, List<StompFrame>, Mono<StompFrame>> onFunction) {
		String sessionId = session.getId();
		String ack = inbound.headers.getFirst(StompHeaders.ID);
		if (ack == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompHeaders.ID));
		}

		ConcurrentHashMap<String, StompFrame> messagesCache = this.ackMessageCache.get(sessionId);
		if (messagesCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "session info not found in cache"));
		}

		StompFrame frame = messagesCache.get(ack);
		if (frame == null) {
			return Mono.just(StompUtils.makeError(inbound, "message info not found in cache"));
		}

		String subscription = frame.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Sent MESSAGE without subscription");

		AckMode ackMode = this.ackModeCache.get(sessionId).get(subscription);
		List<StompFrame> ackOrNackMessages = new LinkedList<>();
		Optional.ofNullable(this.ackSubscriptionCache.get(sessionId))
				.map(map -> map.get(subscription))
				.ifPresent(queue -> {
					if (ackMode == AckMode.CLIENT) {
						synchronized (queue) {
							if (queue.contains(ack)) {
								String id;
								do {
									id = queue.poll();
									if (id == null) break;
									removeMessage.accept(ackMode, messagesCache, ackOrNackMessages, id);
								} while (!id.equals(ack));
							}
						}
					} else if (ackMode == AckMode.CLIENT_INDIVIDUAL) {
						queue.remove(ack);
						removeMessage.accept(ackMode, messagesCache, ackOrNackMessages, ack);
					}
				});
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(inbound), subscription, ack, ackOrNackMessages);
	}

	Mono<StompFrame> handleAck(WebSocketSession session, StompFrame inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id)).ifPresent(messages::add),
				this.server::onAck
		);
	}

	Mono<StompFrame> handleNack(WebSocketSession session, StompFrame inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id))
						.ifPresent(frame -> {
							messages.add(frame);
							log.debug("NACK Frame: ackMode={} message={}", ackMode.toString(), frame);
						}),
				this.server::onNack
		);
	}

	Mono<StompFrame> handleTransactionFrame(WebSocketSession session, StompFrame inbound,
											QuadFunction<WebSocketSession, StompFrame, StompFrame, String, Mono<StompFrame>> onFunction) {
		String transaction = inbound.headers.getFirst(StompUtils.TRANSACTION);
		if (transaction == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, StompUtils.TRANSACTION));
		}
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(inbound), transaction);
	}

	Mono<StompFrame> handleBegin(WebSocketSession session, StompFrame inbound) {
		return handleTransactionFrame(session, inbound, this.server::onBegin);
	}

	Mono<StompFrame> handleCommit(WebSocketSession session, StompFrame inbound) {
		return handleTransactionFrame(session, inbound, this.server::onCommit);
	}

	Mono<StompFrame> handleAbort(WebSocketSession session, StompFrame inbound) {
		return handleTransactionFrame(session, inbound, this.server::onAbort);
	}

	Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, StompFrame>> handleDisconnect(WebSocketSession session) {
		String sessionId = session.getId();
		this.ackModeCache.remove(sessionId);
		return new Pair<>(this.ackSubscriptionCache.remove(sessionId), this.ackMessageCache.remove(sessionId));
	}

	Mono<StompFrame> handleDisconnect(WebSocketSession session, StompFrame inbound) {
		Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, StompFrame>> sessionCaches = handleDisconnect(session);
		return this.server.onDisconnect(session, inbound, StompUtils.makeReceipt(inbound), sessionCaches.one, sessionCaches.two);
	}

	Mono<StompFrame> handleError(WebSocketSession session, StompFrame inbound, StompFrame outbound) {
		Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, StompFrame>> sessionCaches = handleDisconnect(session);
		return this.server.onError(session, inbound, outbound, sessionCaches.one, sessionCaches.two);
	}

	void cacheMessageForAck(WebSocketSession session, StompFrame outbound) {
		if (outbound.command != StompCommand.MESSAGE) {
			return;
		}

		String sessionId = session.getId();

		ConcurrentHashMap<String, AckMode> subscriptionAckMode = this.ackModeCache.get(sessionId);
		if (subscriptionAckMode == null) {
			return;
		}

		String subscription = outbound.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Trying to send MESSAGE without subscription");

		AckMode ackMode = subscriptionAckMode.get(subscription);
		if (ackMode == null || ackMode == AckMode.AUTO) {
			return;
		}

		String ack = UUID.randomUUID().toString();
		this.ackMessageCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
				.put(ack, outbound);
		this.ackSubscriptionCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(subscription, k -> new ConcurrentLinkedQueue<>())
				.add(ack);

		outbound.headers.add(StompHeaders.ACK, ack);
	}

	static class Pair<T1, T2> {

		T1 one;
		T2 two;

		Pair(T1 one, T2 two) {
			this.one = one;
			this.two = two;
		}

	}

	@FunctionalInterface
	interface QuadConsumer<T, U, V, W> {
		void accept(T t, U u, V v, W w);
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
