package org.github.stomp.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.*;

import java.nio.charset.Charset;
import java.util.*;

@Slf4j
public class StompUtils {

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
		return new StompMessage(StompCommand.MESSAGE, headers, bodyCharset, body, false);
	}

	public static StompMessage makeReceipt(String sessionId, StompMessage inbound) {
		String receipt = inbound.getHeaders().getFirst(StompHeaders.RECEIPT);
		if (receipt == null) {
			return null;
		}
		log.debug("Creating receipt for: sessionId={} command={} body={}", sessionId, inbound.getCommandString(), inbound.getBody());
		return new StompMessage(StompCommand.RECEIPT, Map.of(StompHeaderAccessor.STOMP_RECEIPT_ID_HEADER, Collections.singletonList(receipt)));
	}

	static StompMessage makeMalformedError(String sessionId, StompMessage inbound) {
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

}
