package org.github.stomp.server;

import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public interface StompServer {

	/**
	 * Returns web path of the STOMP server endpoint.
	 *
	 * @return The web path of the STOMP server endpoint.
	 */
	String path();

	enum Version {
		v1_0("1.0"), v1_1("1.1"), v1_2("1.2");

		final float floatVersion;
		final String version;

		Version(String version) {
			this.version = version;
			this.floatVersion = Float.parseFloat(version);
		}

		public float version() {
			return this.floatVersion;
		}

		public String toString() {
			return this.version;
		}
	}

	enum AckMode {
		AUTO("auto"), CLIENT("client"), CLIENT_INDIVIDUAL("client-individual");

		final String ackMode;

		AckMode(String ackMode) {
			this.ackMode = ackMode;
		}

		public static AckMode from(String ackMode) {
			if (ackMode == null) {
				return null;
			}
			for (AckMode mode : AckMode.values()) {
				if (mode.ackMode.equalsIgnoreCase(ackMode)) {
					return mode;
				}
			}
			return null;
		}

		public String toString() {
			return this.ackMode;
		}
	}

	/**
	 * Adds STOMP frame sources from which frames are forwarded to the websocket client.
	 *
	 * @param session The session to add frame sources to.
	 * @return The list of sources to propagate frames.
	 */
	default Mono<List<Flux<StompFrame>>> addWebSocketSources(WebSocketSession session) {
		return Mono.empty();
	}

	/**
	 * Adds consumer for each inbound STOMP frame.
	 *
	 * @param session The associated websocket session.
	 * @param inbound The inbound STOMP frame.
	 */
	default Mono<Void> doOnEachInbound(WebSocketSession session, StompFrame inbound) {
		return Mono.empty();
	}

	/**
	 * Adds consumer for each outbound STOMP frame.
	 *
	 * @param session  The associated websocket session.
	 * @param outbound The outbound STOMP frame.
	 */
	default Mono<Void> doOnEachOutbound(WebSocketSession session, StompFrame outbound) {
		return Mono.empty();
	}

	/**
	 * Adds final behavior after websocket closure. The <code>subscriptionCache</code> and <code>frameCache</code> will
	 * only contain valid information upon premature termination of websocket connection by the client.
	 *
	 * @param session           The terminated session.
	 * @param subscriptionCache The map of <code>subscription</code>s mapped to its acknowledgment mode and the queue of unacknowledged outbound <code>ack</code>s expecting an acknowledgment. May be <code>null</code>
	 * @param frameCache        The map of <code>ack</code>s mapped to the unacknowledged outbound frames expecting an acknowledgement. May be <code>null</code>
	 * @see StompServer#onDisconnect(WebSocketSession, StompFrame, StompFrame, Map, Map)
	 * @see StompServer#onError(WebSocketSession, StompFrame, StompFrame, Map, Map)
	 */
	default Mono<Void> doFinally(WebSocketSession session, Map<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache, Map<String, StompFrame> frameCache) {
		return Mono.empty();
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
	default Mono<StompFrame> onStomp(WebSocketSession session, StompFrame inbound, StompFrame outbound, Version version, String host) {
		return Mono.just(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>CONNECT</code> frame from client.
	 *
	 * @param session  The associated websocket session.
	 * @param inbound  The inbound client frame.
	 * @param outbound The potential outbound server frame.
	 * @param version  The negotiated STOMP protocol version.
	 * @param host     The host requested in the client frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompFrame> onConnect(WebSocketSession session, StompFrame inbound, StompFrame outbound, Version version, String host) {
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
	default Mono<StompFrame> onSend(WebSocketSession session, StompFrame inbound, StompFrame outbound, String destination) {
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
	default Mono<StompFrame> onSubscribe(WebSocketSession session, StompFrame inbound, StompFrame outbound, String destination, String subscriptionId) {
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
	default Mono<StompFrame> onUnsubscribe(WebSocketSession session, StompFrame inbound, StompFrame outbound, String subscriptionId) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>ACK</code> frame from client.
	 *
	 * @param session      The associated websocket session.
	 * @param inbound      The inbound client frame.
	 * @param outbound     The potential outbound server frame. May be <code>null</code>
	 * @param subscription The subscription of the <code>ACK</code> frame.
	 * @param id           The id of the <code>ACK</code> frame.
	 * @param ackMessages  The list of ack-ed messages.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompFrame> onAck(WebSocketSession session, StompFrame inbound, StompFrame outbound, String subscription, String id, List<StompFrame> ackMessages) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>NACK</code> frame from client.
	 *
	 * @param session      The associated websocket session.
	 * @param inbound      The inbound client frame.
	 * @param outbound     The potential outbound server frame. May be <code>null</code>
	 * @param subscription The subscription of the <code>ACK</code> frame.
	 * @param id           Thes id of the <code>NACK</code> frame.
	 * @param nackMessages The list of nack-ed messages.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompFrame> onNack(WebSocketSession session, StompFrame inbound, StompFrame outbound, String subscription, String id, List<StompFrame> nackMessages) {
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
	default Mono<StompFrame> onBegin(WebSocketSession session, StompFrame inbound, StompFrame outbound, String transaction) {
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
	default Mono<StompFrame> onCommit(WebSocketSession session, StompFrame inbound, StompFrame outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>ABORT</code> frame from client.
	 *
	 * @param session     The associated websocket session.
	 * @param inbound     The inbound client frame.
	 * @param outbound    The potential outbound server frame. May be <code>null</code>
	 * @param transaction The transaction of the <code>ABORT</code> frame.
	 * @return The final outbound STOMP frame.
	 */
	default Mono<StompFrame> onAbort(WebSocketSession session, StompFrame inbound, StompFrame outbound, String transaction) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon receiving <code>DISCONNECT</code> frame from client prior to connection closure. The
	 * <code>subscriptionCache</code> and <code>frameCache</code> will always contain valid information if
	 * unacknowledged frames exist.
	 *
	 * @param session           The associated websocket session.
	 * @param inbound           The inbound client frame.
	 * @param outbound          The potential outbound server frame. May be <code>null</code>
	 * @param subscriptionCache The map of <code>subscription</code>s mapped to its acknowledgment mode and the queue of unacknowledged outbound <code>ack</code>s expecting an acknowledgment. May be <code>null</code>
	 * @param frameCache        The map of <code>ack</code>s mapped to the unacknowledged outbound frames expecting an acknowledgement. May be <code>null</code>
	 * @see StompServer#doFinally(WebSocketSession, Map, Map)
	 * @see StompServer#onError(WebSocketSession, StompFrame, StompFrame, Map, Map)
	 */
	default Mono<StompFrame> onDisconnect(WebSocketSession session, StompFrame inbound, StompFrame outbound, Map<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache, Map<String, StompFrame> frameCache) {
		return Mono.justOrEmpty(outbound);
	}

	/**
	 * Adds behaviour upon sending <code>ERROR</code> frame to client prior to connection closure. The
	 * <code>subscriptionCache</code> and <code>frameCache</code> will always contain valid information if
	 * unacknowledged frames exist.
	 *
	 * @param session           The associated websocket session.
	 * @param inbound           The inbound client frame.
	 * @param outbound          The potential outbound server frame.
	 * @param subscriptionCache The map of <code>subscription</code>s mapped to its acknowledgment mode and the queue of unacknowledged outbound <code>ack</code>s expecting an acknowledgment. May be <code>null</code>
	 * @param frameCache        The map of <code>ack</code>s mapped to the unacknowledged outbound frames expecting an acknowledgement. May be <code>null</code>
	 * @see StompServer#doFinally(WebSocketSession, Map, Map)
	 * @see StompServer#onDisconnect(WebSocketSession, StompFrame, StompFrame, Map, Map)
	 */
	default Mono<Void> onError(WebSocketSession session, StompFrame inbound, StompFrame outbound, Map<String, Tuple2<AckMode, ConcurrentLinkedQueue<String>>> subscriptionCache, Map<String, StompFrame> frameCache) {
		return Mono.empty();
	}

}
