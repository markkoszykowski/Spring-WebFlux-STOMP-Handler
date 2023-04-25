package org.github.stomp.handler;

import org.github.stomp.data.StompMessage;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public interface StompHandler extends WebSocketHandler {

	enum Version {
		v1_0("1.0"),
		v1_1("1.1"),
		v1_2("1.2");

		private final Float floatVersion;
		private final String version;

		Version(String version) {
			this.version = version;
			this.floatVersion = Float.parseFloat(version);
		}

		public Float version() {
			return floatVersion;
		}

		public String toString() {
			return version;
		}
	}

	/**
	 * Returns supported STOMP versions of implementation.
	 *
	 * @return The list of supported STOMP versions.
	 */
	List<Version> supportedVersions();

	@Override
	default List<String> getSubProtocols() {
		return supportedVersions().stream().map(Version::toString).map(v -> String.format("STOMP %s", v)).collect(Collectors.toList());
	}


	enum AckMode {
		AUTO("auto"),
		CLIENT("client"),
		CLIENT_INDIVIDUAL("client-individual");

		private final String ackMode;

		AckMode(String ackMode) {
			this.ackMode = ackMode;
		}

		public static AckMode from(String ackMode) {
			for (AckMode mode : AckMode.values()) {
				if (mode.toString().equalsIgnoreCase(ackMode)) {
					return mode;
				}
			}
			return null;
		}

		public String toString() {
			return ackMode;
		}
	}


	/**
	 * Adds STOMP frame sources from which frames are forwarded to the websocket client.
	 *
	 * @param session The session to add frame sources to.
	 * @return The list of sources to propagate frames.
	 */
	default Mono<List<Flux<StompMessage>>> addWebSocketSources(WebSocketSession session) {
		return Mono.empty();
	}

	/**
	 * Adds consumer for each inbound STOMP frame.
	 *
	 * @param session  The associated websocket session.
	 * @param inbound The inbound STOMP frame.
	 */
	default void doOnEachInbound(WebSocketSession session, StompMessage inbound) {
	}

	/**
	 * Adds consumer for each outbound STOMP frame.
	 *
	 * @param session  The associated websocket session.
	 * @param outbound The outbound STOMP frame.
	 */
	default void doOnEachOutbound(WebSocketSession session, StompMessage outbound) {
	}

	/**
	 * Adds final behavior after websocket closure. The <code>messagesQueueBySubscription</code> and
	 * <code>messagesCache</code> will only contain valid information upon premature termination of websocket connection
	 * by the client.
	 *
	 * @param session                     The terminated session.
	 * @param signal                      The terminating signal type of the websocket session.
	 * @param messagesQueueBySubscription The map of subscriptionIds mapped to the queue of unacknowledged outbound messageIds expecting an acknowledgement. May be <code>null</code>
	 * @param messagesCache               The map of messageIds mapped to the tuples of subscriptionIds and unacknowledged outbound frames expecting an acknowledgement. May be <code>null</code>
	 * @see StompHandler#onDisconnect(WebSocketSession, StompMessage, StompMessage, Map, Map)
	 * @see StompHandler#onError(WebSocketSession, StompMessage, StompMessage, Map, Map)
	 */
	default void doFinally(WebSocketSession session, SignalType signal, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
	}

	/**
	 * Adds behaviour upon receiving <code>STOMP</code> frame from client.
	 *
	 * @param session  The associated websocket session.
	 * @param inbound  The inbound client frame.
	 * @param outbound The potential outbound server frame.
	 * @param version  The negotiated STOMP protocol version.
	 * @param host     The host requested in the client frame. May be <code>null</code>
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onStomp(WebSocketSession session, StompMessage inbound, StompMessage outbound, Version version, String host) {
		return Mono.just(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>CONNECT</code> frame from client.
	 *
	 * @param session  The associated websocket session.
	 * @param inbound  The inbound client frame.
	 * @param outbound The potential outbound server frame.
	 * @param version  The negotiated STOMP protocol version.
	 * @param host     The host requested in the client frame. May be <code>null</code>
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onConnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, Version version, String host) {
		return Mono.just(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>SEND</code> frame from client.
	 *
	 * @param session     The associated websocket session.
	 * @param inbound     The inbound client frame.
	 * @param outbound    The potential outbound server frame. May be <code>null</code>
	 * @param destination The destination of the <code>SEND</code> frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onSend(WebSocketSession session, StompMessage inbound, StompMessage outbound, String destination) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>SUBSCRIBE</code> frame from client.
	 *
	 * @param session        The associated websocket session.
	 * @param inbound        The inbound client frame.
	 * @param outbound       The potential outbound server frame. May be <code>null</code>
	 * @param destination    The destination of the <code>SUBSCRIBE</code> frame.
	 * @param subscriptionId The subscriptionId of the <code>SUBSCRIBE</code> frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onSubscribe(WebSocketSession session, StompMessage inbound, StompMessage outbound, String destination, String subscriptionId) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>UNSUBSCRIBE</code> frame from client.
	 *
	 * @param session        The associated websocket session.
	 * @param inbound        The inbound client frame.
	 * @param outbound       The potential outbound server frame. May be <code>null</code>
	 * @param subscriptionId The subscriptionId of the <code>UNSUBSCRIBE</code> frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onUnsubscribe(WebSocketSession session, StompMessage inbound, StompMessage outbound, String subscriptionId) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>ACK</code> frame from client.
	 *
	 * @param session     The associated websocket session.
	 * @param inbound     The inbound client frame.
	 * @param outbound    The potential outbound server frame. May be <code>null</code>
	 * @param message     The messageId of the <code>ACK</code> frame.
	 * @param ackMessages The list of tuples of subscriptionIds and ack-ed frames.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onAck(WebSocketSession session, StompMessage inbound, StompMessage outbound, String message, List<Tuple2<String, StompMessage>> ackMessages) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>NACK</code> frame from client.
	 *
	 * @param session      The associated websocket session.
	 * @param inbound      The inbound client frame.
	 * @param outbound     The potential outbound server frame. May be <code>null</code>
	 * @param message      The messageId of the <code>NACK</code> frame.
	 * @param nackMessages The list of tuples of subscriptionIds and nack-ed frames.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onNack(WebSocketSession session, StompMessage inbound, StompMessage outbound, String message, List<Tuple2<String, StompMessage>> nackMessages) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>BEGIN</code> frame from client.
	 *
	 * @param session     The associated websocket session.
	 * @param inbound     The inbound client frame.
	 * @param outbound    The potential outbound server frame.
	 * @param transaction The transaction of the <code>BEGIN</code> frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onBegin(WebSocketSession session, StompMessage inbound, StompMessage outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>COMMIT</code> frame from client.
	 *
	 * @param session     The associated websocket session.
	 * @param inbound     The inbound client frame.
	 * @param outbound    The potential outbound server frame. May be <code>null</code>
	 * @param transaction The transaction of the <code>COMMIT</code> frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompMessage> onCommit(WebSocketSession session, StompMessage inbound, StompMessage outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>ABORT</code> frame from client.
	 *
	 * @param session     The associated websocket session.
	 * @param inbound     The inbound client frame.
	 * @param outbound    The potential outbound server frame. May be <code>null</code>
	 * @param transaction The transaction of the <code>ABORT</code> frame.
	 * @return The final outbound STOMP message.
	 */
	default Mono<StompMessage> onAbort(WebSocketSession session, StompMessage inbound, StompMessage outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>DISCONNECT</code> frame from client prior to connection closure. The
	 * <code>messagesQueueBySubscription</code> and <code>messagesCache</code> will always contain valid information if
	 * unacknowledged frames exist.
	 *
	 * @param session                     The associated websocket session.
	 * @param inbound                     The inbound client frame.
	 * @param outbound                    The potential outbound server frame. May be <code>null</code>
	 * @param messagesQueueBySubscription The map of subscriptionIds mapped to the queue of unacknowledged outbound messageIds expecting an acknowledgement. May be <code>null</code>
	 * @param messagesCache               The map of messageIds mapped to the tuples of subscriptionIds and unacknowledged outbound messages expecting an acknowledgement. May be <code>null</code>
	 * @see StompHandler#doFinally(WebSocketSession, SignalType, Map, Map)
	 * @see StompHandler#onError(WebSocketSession, StompMessage, StompMessage, Map, Map)
	 */
	default Mono<StompMessage> onDisconnect(WebSocketSession session, StompMessage inbound, StompMessage outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon sending <code>ERROR</code> frame to client prior to connection closure. The
	 * <code>messagesQueueBySubscription</code> and <code>messagesCache</code> will always contain valid information if
	 * unacknowledged frames exist.
	 *
	 * @param session                     The associated websocket session.
	 * @param inbound                     The inbound client frame.
	 * @param outbound                    The potential outbound server frame.
	 * @param messagesQueueBySubscription The map of subscriptionIds mapped to the queue of unacknowledged outbound messageIds expecting an acknowledgement. May be <code>null</code>
	 * @param messagesCache               The map of messageIds mapped to the tuples of subscriptionIds and unacknowledged outbound messages expecting an acknowledgement. May be <code>null</code>
	 * @see StompHandler#doFinally(WebSocketSession, SignalType, Map, Map)
	 * @see StompHandler#onDisconnect(WebSocketSession, StompMessage, StompMessage, Map, Map)
	 */
	default Mono<StompMessage> onError(WebSocketSession session, StompMessage inbound, StompMessage outbound, Map<String, ConcurrentLinkedQueue<String>> messagesQueueBySubscription, Map<String, Tuple2<String, StompMessage>> messagesCache) {
		return Mono.just(outbound);
	}

}
