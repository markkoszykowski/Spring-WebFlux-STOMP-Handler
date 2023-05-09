package org.github.stomp.handler;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.data.StompMessage;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.*;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.Map.entry;

@Slf4j
public abstract class AbstractStompHandler implements StompHandler {

	public static final List<Version> SUPPORTED_VERSIONS = List.of(Version.v1_2, Version.v1_1, Version.v1_0);

	@Override
	public List<Version> supportedVersions() {
		return SUPPORTED_VERSIONS;
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, String body) {
		return makeMessage(sessionId, destination, subscription, null, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, Map<String, List<String>> userDefinedHeaders) {
		return makeMessage(sessionId, destination, subscription, userDefinedHeaders, null, null);
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders) {
		return makeMessage(sessionId, destination, subscription, userDefinedHeaders, null, null);
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, Map<String, List<String>> userDefinedHeaders, String body) {
		return makeMessage(sessionId, destination, subscription, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, String body) {
		return makeMessage(sessionId, destination, subscription, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body) {
		return makeMessage(sessionId, destination, subscription, CollectionUtils.toMultiValueMap(userDefinedHeaders), contentType, body);
	}

	public static StompMessage makeMessage(String sessionId, String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body) {
		Assert.notNull(sessionId, "SessionId cannot be null");
		Assert.notNull(destination, "Destination cannot be null");
		Assert.notNull(subscription, "Subscription cannot be null");

		Charset bodyCharset = contentType == null ? null : contentType.getCharset();
		String messageId = UUID.randomUUID().toString();
		String contentTypeString = getContentType(contentType);
		String contentLength = getContentLength(body);
		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>() {{
			add(StompHeaderAccessor.STOMP_DESTINATION_HEADER, destination);
			add(StompHeaderAccessor.STOMP_SUBSCRIPTION_HEADER, subscription);
			add(StompHeaderAccessor.STOMP_MESSAGE_ID_HEADER, messageId);
			add(StompHeaderAccessor.STOMP_CONTENT_LENGTH_HEADER, contentLength);
		}};
		if (contentTypeString != null) {
			headers.add(StompHeaderAccessor.STOMP_CONTENT_TYPE_HEADER, contentTypeString);
		}
		Optional.ofNullable(userDefinedHeaders).stream()
				.map(MultiValueMap::entrySet)
				.flatMap(Set::stream)
				.forEach(pair -> headers.addAll(pair.getKey(), pair.getValue()));
		StompMessage message;
		ConcurrentHashMap<String, AckMode> subscriptionAckMode = ACK_MODE.get(sessionId);
		if (subscriptionAckMode == null) {
			message = new StompMessage(StompCommand.MESSAGE, headers, bodyCharset, body);
		} else {
			headers.add(StompHeaderAccessor.STOMP_ACK_HEADER, messageId);
			message = new StompMessage(StompCommand.MESSAGE, headers, bodyCharset, body);

			ACK_MESSAGE_CACHE.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
					.put(messageId, Tuples.of(subscription, message));

			ACK_SUBSCRIPTION_CACHE.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
					.computeIfAbsent(subscription, k -> new ConcurrentLinkedQueue<>())
					.add(messageId);
		}
		return message;
	}

	public static StompMessage makeReceipt(String sessionId, StompMessage inbound) {
		String receipt = inbound.getHeaders().getFirst(StompHeaders.RECEIPT);
		if (receipt == null) {
			return null;
		}
		log.debug("Creating receipt for: sessionId={} command={} body={}", sessionId, inbound.getCommandString(), inbound.getBody());
		return new StompMessage(StompCommand.RECEIPT, Map.of(StompHeaderAccessor.STOMP_RECEIPT_ID_HEADER, Collections.singletonList(receipt)));
	}

	private static StompMessage makeMalformedError(String sessionId, StompMessage inbound) {
		return makeError(sessionId, inbound, null, String.format("malformed %s frame received", inbound.getCommandString()), null);
	}

	public static StompMessage makeError(String sessionId, StompMessage inbound, MultiValueMap<String, String> userDefinedHeaders, String errorHeader, String errorBody) {
		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>() {{
			add(StompHeaderAccessor.STOMP_MESSAGE_HEADER, errorHeader);
			add(StompHeaderAccessor.STOMP_CONTENT_LENGTH_HEADER, getContentLength(errorBody));
		}};
		if (errorBody != null) {
			headers.add(StompHeaderAccessor.STOMP_CONTENT_TYPE_HEADER, DEFAULT_CONTENT_TYPE_STRING);
		}
		String receipt = inbound.getHeaders().getFirst(StompHeaders.RECEIPT);
		if (receipt != null) {
			headers.add(StompHeaderAccessor.STOMP_RECEIPT_ID_HEADER, receipt);
		}
		Optional.ofNullable(userDefinedHeaders).stream()
				.map(MultiValueMap::entrySet)
				.flatMap(Set::stream)
				.forEach(pair -> headers.addAll(pair.getKey(), pair.getValue()));
		log.error("Creating error for: sessionId={} command={} body={} error={}", sessionId, inbound.getCommandString(), inbound.getBody(), errorHeader);
		return new StompMessage(StompCommand.ERROR, headers, errorBody);
	}


	// Header Keys
	public static final String SESSION = "session";
	public static final String TRANSACTION = "transaction";

	// Header Utils
	private static final MimeType DEFAULT_CONTENT_TYPE = new MimeType(MediaType.TEXT_PLAIN, StompMessage.DEFAULT_CHARSET);
	private static final String DEFAULT_CONTENT_TYPE_STRING = DEFAULT_CONTENT_TYPE.toString();

	private static String getContentLength(String body) {
		return body == null ? "0" : String.valueOf(body.getBytes(StompMessage.DEFAULT_CHARSET).length);
	}

	private static String getContentLength(byte[] body) {
		return body == null ? "0" : String.valueOf(body.length);
	}

	private static String getContentType(MimeType contentType) {
		return contentType == null ? null : contentType.toString();
	}


	// Caches
	// SessionId -> SubscriptionId -> ACK Mode
	private static final ConcurrentHashMap<String, ConcurrentHashMap<String, AckMode>> ACK_MODE = new ConcurrentHashMap<>();
	// SessionId -> MessageId -> (SubscriptionId, StompMessage)
	private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Tuple2<String, StompMessage>>> ACK_MESSAGE_CACHE = new ConcurrentHashMap<>();
	// SessionId -> SubscriptionId -> [MessageId, ...]
	private static final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>> ACK_SUBSCRIPTION_CACHE = new ConcurrentHashMap<>();


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
				.doOnNext(message -> doOnEachInbound(session, message))
				.takeUntil(message -> StompCommand.DISCONNECT.equals(message.getCommand()))
				.flatMap(inbound -> handler.get(inbound.getCommand()).apply(session, inbound)
						.flatMap(outbound -> outbound.error() ? handleError(session, inbound, outbound) : Mono.just(outbound)))
				.takeUntil(StompMessage::error)
				.doOnError(ex -> log.error("Error during WebSocket handling: {}", ex.getMessage()))
				.doFinally(signalType -> session.close().subscribeOn(Schedulers.immediate()).subscribe());
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		return session.send(addWebSocketSources(session)
				.flatMapMany(Flux::merge)
				.mergeWith(sessionReceiver(session))
				.switchIfEmpty(sessionReceiver(session))
				.doOnNext(message -> doOnEachOutbound(session, message))
				.map(message -> message.toWebSocketMessage(session))
		).doFinally(signal -> {
			Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
			doFinally(session, signal, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
		});
	}


	private Mono<StompMessage> handleProtocolNegotiation(WebSocketSession session, StompMessage inbound, QuintFunction<WebSocketSession, StompMessage, StompMessage, Version, String, Mono<StompMessage>> onFunction) {
		String versionsString = inbound.getHeaders().getFirst(StompHeaders.ACCEPT_VERSION);
		Version usingVersion;
		if (versionsString == null) {
			usingVersion = Version.v1_0;
		} else {
			Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = supportedVersions().stream().filter(version -> versionsSet.contains(version.toString())).findFirst().orElse(null);
		}
		String host = inbound.getHeaders().getFirst(StompHeaders.HOST);
		if (usingVersion == null) {
			String serverVersionsHeader = supportedVersions().stream().sorted(Comparator.comparing(Version::version)).map(Version::toString).collect(Collectors.joining(","));
			String serverVersionsBody = supportedVersions().stream().sorted(Comparator.comparing(Version::version)).map(Version::toString).collect(Collectors.joining(" "));
			return Mono.just(makeError(session.getId(), inbound, CollectionUtils.toMultiValueMap(Map.ofEntries(
					entry(StompHeaderAccessor.STOMP_VERSION_HEADER, Collections.singletonList(serverVersionsHeader))
			)), "Unsupported protocol versions", String.format("Supported protocol versions are %s", serverVersionsBody)));
		}
		return onFunction.apply(session, inbound, new StompMessage(StompCommand.CONNECTED, Map.ofEntries(
				entry(StompHeaderAccessor.STOMP_VERSION_HEADER, Collections.singletonList(usingVersion.toString())),
				entry(SESSION, Collections.singletonList(session.getId()))
		)), usingVersion, host);
	}

	private Mono<StompMessage> handleStomp(WebSocketSession session, StompMessage inbound) {
		return handleProtocolNegotiation(session, inbound, this::onStomp);
	}

	private Mono<StompMessage> handleConnect(WebSocketSession session, StompMessage inbound) {
		return handleProtocolNegotiation(session, inbound, this::onConnect);
	}

	private Mono<StompMessage> handleSend(WebSocketSession session, StompMessage inbound) {
		String destination = inbound.getHeaders().getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		return onSend(session, inbound, makeReceipt(session.getId(), inbound), destination);
	}

	private Mono<StompMessage> handleSubscribe(WebSocketSession session, StompMessage inbound) {
		String sessionId = session.getId();
		String destination = inbound.getHeaders().getFirst(StompHeaders.DESTINATION);
		String subscriptionId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (destination == null || subscriptionId == null) {
			return Mono.just(makeMalformedError(sessionId, inbound));
		}
		AckMode ackMode = AckMode.from(inbound.getHeaders().getFirst(StompHeaders.ACK));
		if (ackMode != null && ackMode != AckMode.AUTO) {
			ACK_MODE.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
					.put(subscriptionId, ackMode);
		}
		return onSubscribe(session, inbound, makeReceipt(sessionId, inbound), destination, subscriptionId);
	}

	private Mono<StompMessage> handleUnsubscribe(WebSocketSession session, StompMessage inbound) {
		String sessionId = session.getId();
		String subscriptionId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(makeMalformedError(sessionId, inbound));
		}
		Optional.ofNullable(ACK_MODE.get(sessionId)).ifPresent(map -> map.remove(subscriptionId));

		ConcurrentLinkedQueue<String> messageQueueCache = Optional.ofNullable(ACK_SUBSCRIPTION_CACHE.get(sessionId)).map(map -> map.remove(subscriptionId)).orElse(null);
		if (messageQueueCache != null) {
			ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ACK_MESSAGE_CACHE.get(sessionId);
			if (messagesCache != null) {
				messageQueueCache.forEach(messagesCache::remove);
			}
		}
		return onUnsubscribe(session, inbound, makeReceipt(sessionId, inbound), subscriptionId);
	}

	private Mono<StompMessage> handleAckOrNack(WebSocketSession session, StompMessage inbound,
											   QuadConsumer<AckMode, ConcurrentHashMap<String, Tuple2<String, StompMessage>>, List<Tuple2<String, StompMessage>>, String> removeMessage,
											   QuintFunction<WebSocketSession, StompMessage, StompMessage, String, List<Tuple2<String, StompMessage>>, Mono<StompMessage>> onFunction) {
		String sessionId = session.getId();
		String messageId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (messageId == null) {
			return Mono.just(makeMalformedError(sessionId, inbound));
		}
		ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ACK_MESSAGE_CACHE.get(sessionId);
		if (messagesCache == null) {
			return Mono.just(makeError(sessionId, inbound, null, "Session info not found in cache", null));
		}
		Tuple2<String, StompMessage> messageInfo = messagesCache.get(messageId);
		if (messageInfo == null) {
			return Mono.just(makeError(sessionId, inbound, null, "Message info not found in cache", null));
		}
		String subscriptionId = messageInfo.getT1();
		AckMode ackMode = ACK_MODE.get(sessionId).get(subscriptionId);
		List<Tuple2<String, StompMessage>> ackOrNackMessages = new ArrayList<>();
		Optional.ofNullable(ACK_SUBSCRIPTION_CACHE.get(sessionId))
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
		return onFunction.apply(session, inbound, makeReceipt(sessionId, inbound), messageId, ackOrNackMessages);
	}

	private Mono<StompMessage> handleAck(WebSocketSession session, StompMessage inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id)).ifPresent(messages::add),
				this::onAck
		);
	}

	private Mono<StompMessage> handleNack(WebSocketSession session, StompMessage inbound) {
		return handleAckOrNack(session, inbound,
				(ackMode, cache, messages, id) -> Optional.ofNullable(cache.remove(id))
						.ifPresent(messageInfo -> {
							messages.add(messageInfo);
							log.error("Nack Message: subscription={} ackMode={} message={}", messageInfo.getT1(), ackMode.toString(), messageInfo.getT2());
						}),
				this::onNack
		);
	}

	private Mono<StompMessage> handleTransactionFrame(WebSocketSession session, StompMessage inbound,
													  QuadFunction<WebSocketSession, StompMessage, StompMessage, String, Mono<StompMessage>> onFunction) {
		String transaction = inbound.getHeaders().getFirst(TRANSACTION);
		if (transaction == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		return onFunction.apply(session, inbound, makeReceipt(session.getId(), inbound), transaction);
	}

	private Mono<StompMessage> handleBegin(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, this::onBegin);
	}

	private Mono<StompMessage> handleCommit(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, this::onCommit);
	}

	private Mono<StompMessage> handleAbort(WebSocketSession session, StompMessage inbound) {
		return handleTransactionFrame(session, inbound, this::onAbort);
	}

	private Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> handleDisconnect(WebSocketSession session) {
		String sessionId = session.getId();
		ACK_MODE.remove(sessionId);
		return Tuples.of(
				Optional.ofNullable(ACK_SUBSCRIPTION_CACHE.remove(sessionId)),
				Optional.ofNullable(ACK_MESSAGE_CACHE.remove(sessionId))
		);
	}

	private Mono<StompMessage> handleDisconnect(WebSocketSession session, StompMessage inbound) {
		Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
		return onDisconnect(session, inbound, makeReceipt(session.getId(), inbound), sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
	}

	private Mono<StompMessage> handleError(WebSocketSession session, StompMessage inbound, StompMessage outbound) {
		Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
		return onError(session, inbound, outbound, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
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
