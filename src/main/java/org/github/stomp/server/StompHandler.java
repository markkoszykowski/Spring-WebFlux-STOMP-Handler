package org.github.stomp.server;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.server.StompServer.AckMode;
import org.github.stomp.server.StompServer.Version;
import org.springframework.lang.NonNull;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
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

	@NonNull
	@Override
	public List<String> getSubProtocols() {
		return SUPPORTED_VERSIONS.stream()
				.map(StompServer.Version::toString)
				.map(v -> String.format("STOMP %s", v))
				.collect(Collectors.toList());
	}

	// Caches
	// SessionId -> SubscriptionId -> ACK Mode
	final ConcurrentHashMap<String, ConcurrentHashMap<String, AckMode>> ackModeCache = new ConcurrentHashMap<>();
	// SessionId -> AckId -> (SubscriptionId, StompMessage)
	final ConcurrentHashMap<String, ConcurrentHashMap<String, Tuple2<String, StompMessage>>> ackMessageCache = new ConcurrentHashMap<>();
	// SessionId -> SubscriptionId -> [AckId, ...]
	final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>> ackSubscriptionCache = new ConcurrentHashMap<>();

	final Map<StompCommand, BiFunction<WebSocketSession, StompMessage, Mono<StompMessage>>> handler = Map.ofEntries(
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

	Flux<StompMessage> sessionReceiver(WebSocketSession session) {
		return session.receive()
				.map(StompMessage::from)
				.flatMap(message -> this.server.doOnEachInbound(session, message).then(Mono.just(message)))
				.takeUntil(message -> message.command == StompCommand.DISCONNECT)
				.flatMap(inbound -> this.handler.get(inbound.command).apply(session, inbound)
						.flatMap(outbound -> outbound.error() ? handleError(session, inbound, outbound) : Mono.just(outbound)))
				.takeUntil(StompMessage::error)
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
			Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, Tuple2<String, StompMessage>>> sessionCaches = handleDisconnect(session);
			return this.server.doFinally(session, sessionCaches.one, sessionCaches.two);
		}));
	}

	Mono<StompMessage> handleProtocolNegotiation(WebSocketSession session, StompMessage inbound, QuintFunction<WebSocketSession, StompMessage, StompMessage, Version, String, Mono<StompMessage>> onFunction) {
		String versionsString = inbound.headers.getFirst(StompHeaders.ACCEPT_VERSION);
		Version usingVersion;
		if (versionsString == null) {
			usingVersion = Version.v1_0;
		} else {
			Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = SUPPORTED_VERSIONS.stream().filter(version -> versionsSet.contains(version.toString())).findFirst().orElse(null);
		}

		if (usingVersion == null) {
			String serverVersionsHeader = SUPPORTED_VERSIONS.stream().sorted(Comparator.comparing(Version::version)).map(Version::toString).collect(Collectors.joining(","));
			String serverVersionsBody = SUPPORTED_VERSIONS.stream().sorted(Comparator.comparing(Version::version)).map(Version::toString).collect(Collectors.joining(" "));
			return Mono.just(StompUtils.makeError(inbound, CollectionUtils.toMultiValueMap(Map.of(
					StompUtils.VERSION, Collections.singletonList(serverVersionsHeader)
			)), "unsupported protocol versions", String.format("Supported protocol versions are %s", serverVersionsBody)));
		}

		String host = inbound.headers.getFirst(StompHeaders.HOST);
//		if (host == null) {
//			return Mono.just(StompUtils.makeMalformedError(inbound, "host"));
//		}

		return onFunction.apply(session, inbound, new StompMessage(StompCommand.CONNECTED, new LinkedMultiValueMap<>() {{
			add(StompUtils.VERSION, usingVersion.toString());
			add(StompHeaders.SESSION, session.getId());
		}}, null, null), usingVersion, host);
	}

	Mono<StompMessage> handleStomp(WebSocketSession session, StompMessage inbound) {
		return handleProtocolNegotiation(session, inbound, this.server::onStomp);
	}

	Mono<StompMessage> handleConnect(WebSocketSession session, StompMessage inbound) {
		return handleProtocolNegotiation(session, inbound, this.server::onConnect);
	}

	Mono<StompMessage> handleSend(WebSocketSession session, StompMessage inbound) {
		String destination = inbound.headers.getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, "destination"));
		}
		return this.server.onSend(session, inbound, StompUtils.makeReceipt(inbound), destination);
	}

	Mono<StompMessage> handleSubscribe(WebSocketSession session, StompMessage inbound) {
		String destination = inbound.headers.getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, "destination"));
		}

		String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, "subscription"));
		}

		AckMode ackMode = AckMode.from(inbound.headers.getFirst(StompHeaders.ACK));
		if (ackMode != null && ackMode != AckMode.AUTO) {
			this.ackModeCache.computeIfAbsent(session.getId(), k -> new ConcurrentHashMap<>())
					.put(subscriptionId, ackMode);
		}

		return this.server.onSubscribe(session, inbound, StompUtils.makeReceipt(inbound), destination, subscriptionId);
	}

	Mono<StompMessage> handleUnsubscribe(WebSocketSession session, StompMessage inbound) {
		String sessionId = session.getId();

		String subscriptionId = inbound.headers.getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, "subscription"));
		}

		Optional.ofNullable(this.ackModeCache.get(sessionId)).ifPresent(map -> map.remove(subscriptionId));
		ConcurrentLinkedQueue<String> messageQueueCache = Optional.ofNullable(this.ackSubscriptionCache.get(sessionId)).map(map -> map.remove(subscriptionId)).orElse(null);
		if (messageQueueCache != null) {
			ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = this.ackMessageCache.get(sessionId);
			if (messagesCache != null) {
				messageQueueCache.forEach(messagesCache::remove);
			}
		}
		return this.server.onUnsubscribe(session, inbound, StompUtils.makeReceipt(inbound), subscriptionId);
	}

	Mono<StompMessage> handleAckOrNack(WebSocketSession session, StompMessage inbound,
									   QuadConsumer<AckMode, ConcurrentHashMap<String, Tuple2<String, StompMessage>>, List<Tuple2<String, StompMessage>>, String> removeMessage,
									   QuintFunction<WebSocketSession, StompMessage, StompMessage, String, List<Tuple2<String, StompMessage>>, Mono<StompMessage>> onFunction) {
		String sessionId = session.getId();
		String ackId = inbound.headers.getFirst(StompHeaders.ID);
		if (ackId == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, "id"));
		}

		ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = this.ackMessageCache.get(sessionId);
		if (messagesCache == null) {
			return Mono.just(StompUtils.makeError(inbound, "Session info not found in cache"));
		}

		Tuple2<String, StompMessage> messageInfo = messagesCache.get(ackId);
		if (messageInfo == null) {
			return Mono.just(StompUtils.makeError(inbound, "Message info not found in cache"));
		}

		String subscriptionId = messageInfo.getT1();
		AckMode ackMode = this.ackModeCache.get(sessionId).get(subscriptionId);
		List<Tuple2<String, StompMessage>> ackOrNackMessages = new ArrayList<>();
		Optional.ofNullable(this.ackSubscriptionCache.get(sessionId))
				.map(map -> map.get(subscriptionId))
				.ifPresent(queue -> {
					if (ackMode == AckMode.CLIENT) {
						synchronized (queue) {
							if (queue.contains(ackId)) {
								String id;
								do {
									id = queue.poll();
									if (id == null) break;
									removeMessage.accept(ackMode, messagesCache, ackOrNackMessages, id);
								} while (!id.equals(ackId));
							}
						}
					} else if (ackMode == AckMode.CLIENT_INDIVIDUAL) {
						queue.remove(ackId);
						removeMessage.accept(ackMode, messagesCache, ackOrNackMessages, ackId);
					}
				});
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(inbound), ackId, ackOrNackMessages);
	}

	Mono<StompMessage> handleAck(WebSocketSession session, StompMessage inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id)).ifPresent(messages::add),
				this.server::onAck
		);
	}

	Mono<StompMessage> handleNack(WebSocketSession session, StompMessage inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id))
						.ifPresent(messageInfo -> {
							messages.add(messageInfo);
							log.error("NACK Frame: subscription={} ackMode={} message={}", messageInfo.getT1(), ackMode.toString(), messageInfo.getT2());
						}),
				this.server::onNack
		);
	}

	Mono<StompMessage> handleTransactionFrame(WebSocketSession session, StompMessage inbound,
											  QuadFunction<WebSocketSession, StompMessage, StompMessage, String, Mono<StompMessage>> onFunction) {
		String transaction = inbound.headers.getFirst(StompUtils.TRANSACTION);
		if (transaction == null) {
			return Mono.just(StompUtils.makeMalformedError(inbound, "Transaction header missing."));
		}
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(inbound), transaction);
	}

	Mono<StompMessage> handleBegin(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, this.server::onBegin);
	}

	Mono<StompMessage> handleCommit(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, this.server::onCommit);
	}

	Mono<StompMessage> handleAbort(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, this.server::onAbort);
	}

	Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, Tuple2<String, StompMessage>>> handleDisconnect(WebSocketSession session) {
		String sessionId = session.getId();
		this.ackModeCache.remove(sessionId);
		return new Pair<>(this.ackSubscriptionCache.remove(sessionId), this.ackMessageCache.remove(sessionId));
	}

	Mono<StompMessage> handleDisconnect(WebSocketSession session, StompMessage inbound) {
		Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, Tuple2<String, StompMessage>>> sessionCaches = handleDisconnect(session);
		return this.server.onDisconnect(session, inbound, StompUtils.makeReceipt(inbound), sessionCaches.one, sessionCaches.two);
	}

	Mono<StompMessage> handleError(WebSocketSession session, StompMessage inbound, StompMessage outbound) {
		Pair<Map<String, ConcurrentLinkedQueue<String>>, Map<String, Tuple2<String, StompMessage>>> sessionCaches = handleDisconnect(session);
		return this.server.onError(session, inbound, outbound, sessionCaches.one, sessionCaches.two);
	}

	void cacheMessageForAck(WebSocketSession session, StompMessage outbound) {
		if (outbound.command != StompCommand.MESSAGE) {
			return;
		}
		String sessionId = session.getId();
		ConcurrentHashMap<String, AckMode> subscriptionAckMode = this.ackModeCache.get(sessionId);
		if (subscriptionAckMode == null) {
			return;
		}
		String ackId = UUID.randomUUID().toString();
		String subscription = outbound.headers.getFirst(StompHeaders.SUBSCRIPTION);
		Assert.notNull(subscription, "Trying to send MESSAGE without subscription");
		this.ackMessageCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
				.put(ackId, Tuples.of(subscription, outbound));
		this.ackSubscriptionCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(subscription, k -> new ConcurrentLinkedQueue<>())
				.add(ackId);
		outbound.headers.add(StompHeaders.ACK, ackId);
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

}
