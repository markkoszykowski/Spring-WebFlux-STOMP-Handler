package org.github.stomp.server;

import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.MultiValueMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StompUtils {

	// Header Keys
	public static final String MESSAGE = "message";
	public static final String TRANSACTION = "transaction";
	public static final String VERSION = "version";

	// Header Utils
	static final MimeType DEFAULT_CONTENT_TYPE = new MimeType(MediaType.TEXT_PLAIN, StompFrame.DEFAULT_CHARSET);

	// Helper Functions
	static <K, V> MultiValueMap<K, V> toMultiValueMap(Map<K, List<V>> map) {
		return map != null ? CollectionUtils.toMultiValueMap(map) : null;
	}

	static String getContentLength(byte[] body) {
		return body != null ? String.valueOf(body.length) : "0";
	}

	static String getContentType(MimeType contentType) {
		return contentType != null ? contentType.toString() : null;
	}

	// Make Functions
	public static StompFrame makeMessage(String destination, String subscription, String body) {
		if (body != null) {
			return makeMessage(destination, subscription, null, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		} else {
			return makeMessage(destination, subscription, null, null, null);
		}
	}

	public static StompFrame makeMessage(String destination, String subscription, MimeType contentType, byte[] body) {
		return makeMessage(destination, subscription, null, contentType, body);
	}

	public static StompFrame makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders) {
		return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), null, null);
	}

	public static StompFrame makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders) {
		return makeMessage(destination, subscription, userDefinedHeaders, null, null);
	}

	public static StompFrame makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, String body) {
		if (body != null) {
			return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		} else {
			return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), null, null);
		}
	}

	public static StompFrame makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, String body) {
		if (body != null) {
			return makeMessage(destination, subscription, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		} else {
			return makeMessage(destination, subscription, userDefinedHeaders, null, null);
		}
	}

	public static StompFrame makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body) {
		return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), contentType, body);
	}

	public static StompFrame makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body) {
		Assert.notNull(destination, "'destination' must not be null");
		Assert.notNull(subscription, "'subscription' must not be null");

		MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
		headers.add(StompHeaders.DESTINATION, destination);
		headers.add(StompHeaders.SUBSCRIPTION, subscription);
		headers.add(StompHeaders.MESSAGE_ID, UUID.randomUUID().toString());
		headers.add(StompHeaders.CONTENT_LENGTH, getContentLength(body));

		String contentTypeString = getContentType(contentType);
		if (contentTypeString != null) {
			headers.add(StompHeaders.CONTENT_TYPE, contentTypeString);
		}

		if (userDefinedHeaders != null) {
			headers.addAll(userDefinedHeaders);
		}

		return new StompFrame(StompCommand.MESSAGE, headers, contentType != null ? contentType.getCharset() : null, body);
	}

	public static StompFrame makeReceipt(StompFrame inbound) {
		Assert.notNull(inbound, "'inbound' must not be null");

		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt == null) {
			return null;
		}

		MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
		headers.add(StompHeaders.RECEIPT_ID, receipt);

		return new StompFrame(StompCommand.RECEIPT, headers, null, null);
	}

	static StompFrame makeMalformedError(StompFrame inbound, String missingHeader) {
		byte[] body = ("The frame:\n-----\n" + inbound + "\n-----\nDid not contain a " + missingHeader +
				" header, which is REQUIRED for frame propagation.").getBytes(StompFrame.DEFAULT_CHARSET);
		return makeError(inbound, "malformed frame received", null, DEFAULT_CONTENT_TYPE, body);
	}


	public static StompFrame makeError(StompFrame inbound, String errorHeader) {
		return makeError(inbound, errorHeader, null, null, null);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, String body) {
		if (body != null) {
			return makeError(inbound, errorHeader, null, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		} else {
			return makeError(inbound, errorHeader, null, null, null);
		}
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, MimeType contentType, byte[] body) {
		return makeError(inbound, errorHeader, null, contentType, body);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders) {
		return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), null, null);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders) {
		return makeError(inbound, errorHeader, userDefinedHeaders, null, null);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders, String body) {
		if (body != null) {
			return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		} else {
			return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), null, null);
		}
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders, String body) {
		if (body != null) {
			return makeError(inbound, errorHeader, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		} else {
			return makeError(inbound, errorHeader, userDefinedHeaders, null, null);
		}
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body) {
		return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), contentType, body);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body) {
		Assert.notNull(inbound, "'inbound' must not be null");
		Assert.notNull(errorHeader, "'errorHeader' must not be null");

		MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>());
		headers.add(MESSAGE, errorHeader);
		headers.add(StompHeaders.CONTENT_LENGTH, getContentLength(body));

		String contentTypeString = getContentType(contentType);
		if (contentTypeString != null) {
			headers.add(StompHeaders.CONTENT_TYPE, contentTypeString);
		}

		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt != null) {
			headers.add(StompHeaders.RECEIPT_ID, receipt);
		}

		if (userDefinedHeaders != null) {
			headers.addAll(userDefinedHeaders);
		}

		return new StompFrame(StompCommand.ERROR, headers, contentType != null ? contentType.getCharset() : null, body);
	}

}
