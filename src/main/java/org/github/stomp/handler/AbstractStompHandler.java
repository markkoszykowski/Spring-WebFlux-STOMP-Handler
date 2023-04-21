package org.github.stomp.handler;

import lombok.extern.slf4j.Slf4j;
import org.github.stomp.data.StompMessage;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.*;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Map.entry;

@Slf4j
public abstract class AbstractStompHandler implements StompHandler {

	String VERSIONS_STRING = IntStream.range(0, VERSIONS.size()).mapToObj(i -> VERSIONS.get(VERSIONS.size() - i - 1)).collect(Collectors.joining(" "));

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
		ConcurrentHashMap<String, String> subscriptionAckMode = ACK_MODE.get(sessionId);
		if (subscriptionAckMode == null) {
			message = new StompMessage(StompCommand.MESSAGE, headers, bodyCharset, body);
		} else {
			headers.add(StompHeaderAccessor.STOMP_ACK_HEADER, messageId);
			message = new StompMessage(StompCommand.MESSAGE, headers, bodyCharset, body);

			ACK_MESSAGE_CACHE.compute(sessionId, (k, v) -> v == null ? new ConcurrentHashMap<>() : v)
					.put(messageId, Tuples.of(subscription, message));

			ACK_SUBSCRIPTION_CACHE.compute(sessionId, (k, v) -> v == null ? new ConcurrentHashMap<>() : v)
					.compute(subscription, (k, v) -> v == null ? new ConcurrentLinkedQueue<>() : v)
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
		return Optional.ofNullable(body).map(b -> body.getBytes(StompMessage.DEFAULT_CHARSET)).map(AbstractStompHandler::getContentLength).orElse("0");
	}

	private static String getContentLength(byte[] body) {
		return String.valueOf(Optional.ofNullable(body).map(bytes -> bytes.length).orElse(0));
	}

	private static String getContentType(MimeType contentType) {
		return Optional.ofNullable(contentType).map(MimeType::toString).orElse(null);
	}


	// ACK Modes
	public static final String CLIENT_ACK_MODE = "client";
	public static final String CLIENT_INDIVIDUAL_ACK_MODE = "client-individual";

	// Caches
	// SessionID -> SubscriptionID -> ACK Mode
	private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> ACK_MODE = new ConcurrentHashMap<>();
	// SessionID -> MessageID -> (SubscriptionID, StompMessage)
	private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Tuple2<String, StompMessage>>> ACK_MESSAGE_CACHE = new ConcurrentHashMap<>();
	// SessionID -> SubscriptionID -> [MessageID, ...]
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
				.doOnNext(message -> log.debug("Received WebSocket Message:\n{}", message))
				.takeUntil(message -> StompCommand.DISCONNECT.equals(message.getCommand()))
				.flatMap(inbound -> handler.get(inbound.getCommand()).apply(session, inbound)
						.flatMap(outbound -> outbound.error() ? handleError(session, inbound, outbound) : Mono.just(outbound)))
				.takeUntil(StompMessage::error)
				.doOnError(ex ->
						log.error("Error during WebSocket handling: {}", ex.getMessage())
				);
	}

	@Override
	public Mono<Void> handle(WebSocketSession session) {
		return session.send(addWebSocketSources(session)
				.flatMapMany(sources -> {
					Flux<StompMessage> outputFlux = sessionReceiver(session);
					for (Flux<StompMessage> source : sources) {
						outputFlux = Flux.merge(outputFlux, source);
					}
					return outputFlux;
				})
				.switchIfEmpty(sessionReceiver(session))
				.doOnNext(message -> doOnEach(session, message))
				.map(message -> new WebSocketMessage(WebSocketMessage.Type.TEXT, session.bufferFactory().wrap(message.toByteBuffer())))
		).doFinally(signal -> {
			Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
			doFinally(session, signal, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
		});
	}


	private Tuple2<Optional<String>, StompMessage> handleConnectionHandshake(WebSocketSession session, StompMessage inbound) {
		String versionsString = inbound.getHeaders().getFirst(StompHeaders.ACCEPT_VERSION);
		String usingVersion;
		if (versionsString == null) {
			usingVersion = v1_0;
		} else {
			Set<String> versionsSet = Set.of(versionsString.split(","));
			usingVersion = VERSIONS.stream().filter(versionsSet::contains).findFirst().orElse(null);
		}
		if (usingVersion == null) {
			return Tuples.of(Optional.empty(), makeError(session.getId(), inbound, CollectionUtils.toMultiValueMap(Map.ofEntries(
					entry(StompHeaderAccessor.STOMP_VERSION_HEADER, Collections.singletonList(VERSIONS_STRING)),
					entry(StompHeaderAccessor.STOMP_CONTENT_TYPE_HEADER, Collections.singletonList(DEFAULT_CONTENT_TYPE_STRING))
			)), "Unsupported protocol versions", String.format("Supported protocol versions are %s", VERSIONS_STRING)));
		}
		return Tuples.of(Optional.of(usingVersion), new StompMessage(StompCommand.CONNECTED, Map.ofEntries(
				entry(StompHeaderAccessor.STOMP_VERSION_HEADER, Collections.singletonList(usingVersion)),
				entry(SESSION, Collections.singletonList(session.getId()))
		)));
	}

	private Mono<StompMessage> handleStomp(WebSocketSession session, StompMessage inbound) {
		Tuple2<Optional<String>, StompMessage> connectionInfo = handleConnectionHandshake(session, inbound);
		if (connectionInfo.getT2().error()) {
			return Mono.just(connectionInfo.getT2());
		}
		return onStomp(session, inbound, connectionInfo.getT2(), connectionInfo.getT1().orElse(null));
	}

	private Mono<StompMessage> handleConnect(WebSocketSession session, StompMessage inbound) {
		Tuple2<Optional<String>, StompMessage> connectionInfo = handleConnectionHandshake(session, inbound);
		if (connectionInfo.getT2().error()) {
			return Mono.just(connectionInfo.getT2());
		}
		return onConnect(session, inbound, connectionInfo.getT2(), connectionInfo.getT1().orElse(null));
	}

	private Mono<StompMessage> handleSend(WebSocketSession session, StompMessage inbound) {
		String destination = inbound.getHeaders().getFirst(StompHeaders.DESTINATION);
		if (destination == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onSend(session, inbound, outbound, destination);
	}

	private Mono<StompMessage> handleSubscribe(WebSocketSession session, StompMessage inbound) {
		String destination = inbound.getHeaders().getFirst(StompHeaders.DESTINATION);
		String subscriptionId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (destination == null || subscriptionId == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		String ackMode = inbound.getHeaders().getFirst(StompHeaders.ACK);
		if (CLIENT_ACK_MODE.equalsIgnoreCase(ackMode) || CLIENT_INDIVIDUAL_ACK_MODE.equalsIgnoreCase(ackMode)) {
			ACK_MODE.compute(session.getId(), (k, v) -> v == null ? new ConcurrentHashMap<>() : v)
					.put(subscriptionId, ackMode.toLowerCase());
		}
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onSubscribe(session, inbound, outbound, destination, subscriptionId);
	}

	private Mono<StompMessage> handleUnsubscribe(WebSocketSession session, StompMessage inbound) {
		String subscriptionId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (subscriptionId == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		String sessionId = session.getId();
		Optional.ofNullable(ACK_MODE.get(sessionId)).ifPresent(map -> map.remove(subscriptionId));

		ConcurrentLinkedQueue<String> messageQueueCache = Optional.ofNullable(ACK_SUBSCRIPTION_CACHE.get(sessionId)).map(map -> map.remove(subscriptionId)).orElse(null);
		if (messageQueueCache != null) {
			ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ACK_MESSAGE_CACHE.get(sessionId);
			if (messagesCache != null) {
				messageQueueCache.forEach(messagesCache::remove);
			}
		}
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onUnsubscribe(session, inbound, outbound, subscriptionId);
	}

	private Mono<StompMessage> handleAck(WebSocketSession session, StompMessage inbound) {
		String messageId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (messageId == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		String sessionId = session.getId();
		ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ACK_MESSAGE_CACHE.get(sessionId);
		if (messagesCache == null) {
			return Mono.just(makeError(session.getId(), inbound, null, "Session info not found in cache", null));
		}
		Tuple2<String, StompMessage> messageInfo = messagesCache.get(messageId);
		if (messageInfo == null) {
			return Mono.just(makeError(session.getId(), inbound, null, "Message info not found in cache", null));
		}
		String subscriptionId = messageInfo.getT1();
		String ackMode = ACK_MODE.get(sessionId).get(subscriptionId);
		List<Tuple2<String, StompMessage>> ackMessages = new ArrayList<>();
		Optional.ofNullable(ACK_SUBSCRIPTION_CACHE.get(sessionId))
				.map(map -> map.get(subscriptionId))
				.ifPresent(queue -> {
					if (CLIENT_ACK_MODE.equals(ackMode)) {
						synchronized (queue) {
							if (queue.contains(messageId)) {
								String id;
								do {
									id = queue.poll();
									if (id == null) break;
									Optional.ofNullable(messagesCache.remove(id)).ifPresent(ackMessages::add);
								} while (!id.equals(messageId));
							}
						}
					} else if (CLIENT_INDIVIDUAL_ACK_MODE.equals(ackMode)) {
						queue.remove(messageId);
						Optional.ofNullable(messagesCache.remove(messageId)).ifPresent(ackMessages::add);
					}
				});
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onAck(session, inbound, outbound, messageId, ackMessages);
	}

	private Mono<StompMessage> handleNack(WebSocketSession session, StompMessage inbound) {
		String messageId = inbound.getHeaders().getFirst(StompHeaders.ID);
		if (messageId == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		String sessionId = session.getId();
		ConcurrentHashMap<String, Tuple2<String, StompMessage>> messagesCache = ACK_MESSAGE_CACHE.get(sessionId);
		if (messagesCache == null) {
			return Mono.just(makeError(session.getId(), inbound, null, "Session info not found in cache", null));
		}
		Tuple2<String, StompMessage> messageInfo = messagesCache.get(messageId);
		if (messageInfo == null) {
			return Mono.just(makeError(session.getId(), inbound, null, "Message info not found in cache", null));
		}
		String subscriptionId = messageInfo.getT1();
		String ackMode = ACK_MODE.get(sessionId).get(subscriptionId);
		List<Tuple2<String, StompMessage>> nackMessages = new ArrayList<>();
		Optional.ofNullable(ACK_SUBSCRIPTION_CACHE.get(sessionId))
				.map(map -> map.get(subscriptionId))
				.ifPresent(queue -> {
					if (CLIENT_ACK_MODE.equals(ackMode)) {
						synchronized (queue) {
							if (queue.contains(messageId)) {
								String id;
								do {
									id = queue.poll();
									if (id == null) break;
									Optional.ofNullable(messagesCache.remove(id))
											.map(tuple -> {
												nackMessages.add(tuple);
												return tuple.getT2();
											})
											.map(StompMessage::getBody)
											.ifPresent(body ->
													log.error("Nack Message: subscription={} ackMode={} message={}", subscriptionId, ackMode, body)
											);
								} while (!id.equals(messageId));
							}
						}
					} else if (CLIENT_INDIVIDUAL_ACK_MODE.equals(ackMode)) {
						queue.remove(messageId);
						Optional.ofNullable(messagesCache.remove(messageId)).ifPresent(nackMessages::add);
						log.error("Nack Message: subscription={} ackMode={} message={}", subscriptionId, ackMode, messageInfo.getT2().getBody());

					}
				});
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onNack(session, inbound, outbound, messageId, nackMessages);
	}

	private Mono<StompMessage> handleBegin(WebSocketSession session, StompMessage inbound) {
		String transaction = inbound.getHeaders().getFirst(TRANSACTION);
		if (transaction == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onBegin(session, inbound, outbound, transaction);
	}

	private Mono<StompMessage> handleCommit(WebSocketSession session, StompMessage inbound) {
		String transaction = inbound.getHeaders().getFirst(TRANSACTION);
		if (transaction == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onCommit(session, inbound, outbound, transaction);
	}

	private Mono<StompMessage> handleAbort(WebSocketSession session, StompMessage inbound) {
		String transaction = inbound.getHeaders().getFirst(TRANSACTION);
		if (transaction == null) {
			return Mono.just(makeMalformedError(session.getId(), inbound));
		}
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onAbort(session, inbound, outbound, transaction);
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
		StompMessage outbound = makeReceipt(session.getId(), inbound);
		return onDisconnect(session, inbound, outbound, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
	}

	private Mono<StompMessage> handleError(WebSocketSession session, StompMessage inbound, StompMessage outbound) {
		Tuple2<Optional<Map<String, ConcurrentLinkedQueue<String>>>, Optional<Map<String, Tuple2<String, StompMessage>>>> sessionCaches = handleDisconnect(session);
		return onError(session, inbound, outbound, sessionCaches.getT1().orElse(null), sessionCaches.getT2().orElse(null));
	}

}
