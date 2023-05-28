package org.github.stomp.server;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.server.StompServer.AckMode;
import org.github.stomp.server.StompServer.Version;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
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

	public StompHandler(StompServer server) {
		this.server = server;
	}

	public static final List<Version> SUPPORTED_VERSIONS = List.of(Version.v1_2, Version.v1_1, Version.v1_0);

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
	// SessionId -> MessageId -> (SubscriptionId, StompMessage)
	final ConcurrentHashMap<String, ConcurrentHashMap<String, Tuple2<String, StompMessage>>> ackMessageCache = new ConcurrentHashMap<>();
	// SessionId -> SubscriptionId -> [MessageId, ...]
	final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>> ackSubscriptionCache = new ConcurrentHashMap<>();

	private final Map<StompCommand, BiFunction<WebSocketSession, StompMessage, Mono<StompMessage>>> handler = Map.ofEntries(
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

	private Flux<StompMessage> sessionReceiver(WebSocketSession session) {
		return session.receive()
				.map(StompMessage::from)
				.flatMap(message -> server.doOnEachInbound(session, message).then(Mono.just(message)))
				.takeUntil(message -> message.getCommand() == StompCommand.DISCONNECT)
				.flatMap(inbound -> handler.get(inbound.getCommand()).apply(session, inbound)
						.flatMap(outbound -> outbound.error() ? handleError(session, inbound, outbound) : Mono.just(outbound)))
				.takeUntil(StompMessage::error)
				.doOnNext(outbound -> cacheMessageForAck(session, outbound))
				.doOnError(ex -> log.error("Error during WebSocket handling: {}", ex.getMessage()))
				.doFinally(signalType -> session.close().subscribeOn(Schedulers.immediate()).subscribe());
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		return session.send(server.addWebSocketSources(session)
				.flatMapMany(Flux::merge)
				.mergeWith(sessionReceiver(session))
				.switchIfEmpty(sessionReceiver(session))
				.flatMap(message -> server.doOnEachOutbound(session, message).then(Mono.just(message)))
				.map(message -> message.toWebSocketMessage(session))
		).then(Mono.defer(() -> {
			Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
			return server.doFinally(session, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
		}));
	}

	private Mono<StompMessage> handleProtocolNegotiation(WebSocketSession session, StompMessage inbound, QuintFunction<WebSocketSession, StompMessage, StompMessage, Version, String, Mono<StompMessage>> onFunction) {
		String versionsString = inbound.getHeaders().getFirst(StompHeaders.ACCEPT_VERSION);
		Version usingVersion;
		if (versionsString == null) {
			usingVersion = Version.v1_0;
		} else {
			Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = SUPPORTED_VERSIONS.stream().filter(version -> versionsSet.contains(version.toString())).findFirst().orElse(null);
		}
		String host = inbound.getHeaders().getFirst(StompHeaders.HOST);
		if (usingVersion == null) {
			String serverVersionsHeader = SUPPORTED_VERSIONS.stream().sorted(Comparator.comparing(Version::version)).map(Version::toString).collect(Collectors.joining(","));
			String serverVersionsBody = SUPPORTED_VERSIONS.stream().sorted(Comparator.comparing(Version::version)).map(Version::toString).collect(Collectors.joining(" "));
			return Mono.just(StompUtils.makeError(session.getId(), inbound, CollectionUtils.toMultiValueMap(Map.ofEntries(
					entry(StompHeaderAccessor.STOMP_VERSION_HEADER, Collections.singletonList(serverVersionsHeader))
			)), "Unsupported protocol versions", String.format("Supported protocol versions are %s", serverVersionsBody)));
		}
		return onFunction.apply(session, inbound, new StompMessage(StompCommand.CONNECTED, new LinkedMultiValueMap<>() {{
			add(StompHeaderAccessor.STOMP_VERSION_HEADER, usingVersion.toString());
			add(StompUtils.SESSION, session.getId());
		}}, false), usingVersion, host);
	}

	private Mono<StompMessage> handleStomp(WebSocketSession session, StompMessage inbound) {
		return handleProtocolNegotiation(session, inbound, server::onStomp);
	}

	private Mono<StompMessage> handleConnect(WebSocketSession session, StompMessage inbound) {
		return handleProtocolNegotiation(session, inbound, server::onConnect);
	}

	private Mono<StompMessage> handleSend(WebSocketSession session, StompMessage inbound) {
		String destination = inbound.getHeaders().getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(StompUtils.makeMalformedError(session.getId(), inbound));
		}
		return server.onSend(session, inbound, StompUtils.makeReceipt(session.getId(), inbound), destination);
	}

	private Mono<StompMessage> handleSubscribe(WebSocketSession session, StompMessage inbound) {
		String sessionId = session.getId();
		String destination = inbound.getHeaders().getFirst(StompHeaders.DESTINATION);
		String subscriptionId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (destination == null || subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(sessionId, inbound));
		}
		AckMode ackMode = AckMode.from(inbound.getHeaders().getFirst(StompHeaders.ACK));
		if (ackMode != null && ackMode != AckMode.AUTO) {
			this.ackModeCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
					.put(subscriptionId, ackMode);
		}
		return server.onSubscribe(session, inbound, StompUtils.makeReceipt(sessionId, inbound), destination, subscriptionId);
	}

	private Mono<StompMessage> handleUnsubscribe(WebSocketSession session, StompMessage inbound) {
		String sessionId = session.getId();
		String subscriptionId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(StompUtils.makeMalformedError(sessionId, inbound));
		}
		Optional.ofNullable(ackModeCache.get(sessionId)).ifPresent(map -> map.remove(subscriptionId));

		ConcurrentLinkedQueue<String> messageQueueCache = Optional.ofNullable(ackSubscriptionCache.get(sessionId)).map(map -> map.remove(subscriptionId)).orElse(null);
		if (messageQueueCache != null) {
			ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ackMessageCache.get(sessionId);
			if (messagesCache != null) {
				messageQueueCache.forEach(messagesCache::remove);
			}
		}
		return server.onUnsubscribe(session, inbound, StompUtils.makeReceipt(sessionId, inbound), subscriptionId);
	}

	private Mono<StompMessage> handleAckOrNack(WebSocketSession session, StompMessage inbound,
											   QuadConsumer<AckMode, ConcurrentHashMap<String, Tuple2<String, StompMessage>>, List<Tuple2<String, StompMessage>>, String> removeMessage,
											   QuintFunction<WebSocketSession, StompMessage, StompMessage, String, List<Tuple2<String, StompMessage>>, Mono<StompMessage>> onFunction) {
		String sessionId = session.getId();
		String messageId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (messageId == null) {
			return Mono.just(StompUtils.makeMalformedError(sessionId, inbound));
		}
		ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ackMessageCache.get(sessionId);
		if (messagesCache == null) {
			return Mono.just(StompUtils.makeError(sessionId, inbound, null, "Session info not found in cache", null));
		}
		Tuple2<String, StompMessage> messageInfo = messagesCache.get(messageId);
		if (messageInfo == null) {
			return Mono.just(StompUtils.makeError(sessionId, inbound, null, "Message info not found in cache", null));
		}
		String subscriptionId = messageInfo.getT1();
		AckMode ackMode = this.ackModeCache.get(sessionId).get(subscriptionId);
		List<Tuple2<String, StompMessage>> ackOrNackMessages = new ArrayList<>();
		Optional.ofNullable(ackSubscriptionCache.get(sessionId))
				.map(map -> map.get(subscriptionId))
				.ifPresent(queue -> {
					if (ackMode == AckMode.CLIENT) {
						synchronized (queue) {
							if (queue.contains(messageId)) {
								String id;
								do {
									id = queue.poll();
									if (id == null) break;
									removeMessage.accept(ackMode, messagesCache, ackOrNackMessages, id);
								} while (!id.equals(messageId));
							}
						}
					} else if (ackMode == AckMode.CLIENT_INDIVIDUAL) {
						queue.remove(messageId);
						removeMessage.accept(ackMode, messagesCache, ackOrNackMessages, messageId);
					}
				});
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(sessionId, inbound), messageId, ackOrNackMessages);
	}

	private Mono<StompMessage> handleAck(WebSocketSession session, StompMessage inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id)).ifPresent(messages::add),
				server::onAck
		);
	}

	private Mono<StompMessage> handleNack(WebSocketSession session, StompMessage inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id))
						.ifPresent(messageInfo -> {
							messages.add(messageInfo);
							log.error("Nack Message: subscription={} ackMode={} message={}", messageInfo.getT1(), ackMode.toString(), messageInfo.getT2());
						}),
				server::onNack
		);
	}

	private Mono<StompMessage> handleTransactionFrame(WebSocketSession session, StompMessage inbound,
													  QuadFunction<WebSocketSession, StompMessage, StompMessage, String, Mono<StompMessage>> onFunction) {
		String transaction = inbound.getHeaders().getFirst(StompUtils.TRANSACTION);
		if (transaction == null) {
			return Mono.just(StompUtils.makeMalformedError(session.getId(), inbound));
		}
		return onFunction.apply(session, inbound, StompUtils.makeReceipt(session.getId(), inbound), transaction);
	}

	private Mono<StompMessage> handleBegin(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, server::onBegin);
	}

	private Mono<StompMessage> handleCommit(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, server::onCommit);
	}

	private Mono<StompMessage> handleAbort(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, server::onAbort);
	}

	private Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> handleDisconnect(WebSocketSession session) {
		String sessionId = session.getId();
		ackModeCache.remove(sessionId);
		return Tuples.of(
				Optional.ofNullable(ackSubscriptionCache.remove(sessionId)),
				Optional.ofNullable(ackMessageCache.remove(sessionId))
		);
	}

	private Mono<StompMessage> handleDisconnect(WebSocketSession session, StompMessage inbound) {
		Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
		return server.onDisconnect(session, inbound, StompUtils.makeReceipt(session.getId(), inbound), sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
	}

	private Mono<StompMessage> handleError(WebSocketSession session, StompMessage inbound, StompMessage outbound) {
		Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
		return server.onError(session, inbound, outbound, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
	}

	private void cacheMessageForAck(WebSocketSession session, StompMessage outbound) {
		if (outbound.getCommand() != StompCommand.MESSAGE) {
			return;
		}
		String sessionId = session.getId();
		ConcurrentHashMap<String, AckMode> subscriptionAckMode = ackModeCache.get(sessionId);
		if (subscriptionAckMode == null) {
			return;
		}
		String messageId = UUID.randomUUID().toString();
		String subscription = outbound.getHeaders().getFirst(StompHeaderAccessor.STOMP_SUBSCRIPTION_HEADER);
		Assert.notNull(subscription, "Trying to send MESSAGE without subscription");
		ackMessageCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
				.put(messageId, Tuples.of(subscription, outbound));
		ackSubscriptionCache.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(subscription, k -> new ConcurrentLinkedQueue<>())
				.add(messageId);
		outbound.headers.add(StompHeaderAccessor.STOMP_ACK_HEADER, messageId);
	}

	@FunctionalInterface
	public interface QuadConsumer<T, U, V, W> {
		void accept(T t, U u, V v, W w);
	}

	@FunctionalInterface
	public interface QuadFunction<T, U, V, W, R> {
		R apply(T t, U u, V v, W w);
	}

	@FunctionalInterface
	public interface QuintFunction<T, U, V, W, X, R> {
		R apply(T t, U u, V v, W w, X x);
	}

}
