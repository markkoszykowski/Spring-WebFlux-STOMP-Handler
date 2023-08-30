package org.github.stomp.server;

import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.MultiValueMap;

import java.util.*;

public class StompUtils {

	// Header Keys
	public static final String MESSAGE = "message";
	public static final String TRANSACTION = "transaction";
	public static final String VERSION = "version";

	// Header Utils
	static final MimeType DEFAULT_CONTENT_TYPE = new MimeType(MediaType.TEXT_PLAIN, StompFrame.DEFAULT_CHARSET);

	// Helper Functions
	static <K, V> MultiValueMap<K, V> toMultiValueMap(Map<K, List<V>> map) {
		return map == null ? null : CollectionUtils.toMultiValueMap(map);
	}

	static String getContentLength(byte[] body) {
		return body == null ? "0" : String.valueOf(body.length);
	}

	static String getContentType(MimeType contentType) {
		return contentType == null ? null : contentType.toString();
	}

	// Make Functions
	public static StompFrame makeMessage(String destination, String subscription, String body) {
		if (body == null) {
			return makeMessage(destination, subscription, null, null, null);
		} else {
			return makeMessage(destination, subscription, null, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
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
		if (body == null) {
			return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), null, null);
		} else {
			return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		}
	}

	public static StompFrame makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, String body) {
		if (body == null) {
			return makeMessage(destination, subscription, userDefinedHeaders, null, null);
		} else {
			return makeMessage(destination, subscription, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		}
	}

	public static StompFrame makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body) {
		return makeMessage(destination, subscription, toMultiValueMap(userDefinedHeaders), contentType, body);
	}

	public static StompFrame makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body) {
		Assert.notNull(destination, "'destination' must not be null");
		Assert.notNull(subscription, "'subscription' must not be null");

		MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>() {{
			put(StompHeaders.DESTINATION, new ArrayList<>(1) {{
				add(destination);
			}});
			put(StompHeaders.SUBSCRIPTION, new ArrayList<>(1) {{
				add(subscription);
			}});
			put(StompHeaders.MESSAGE_ID, new ArrayList<>(1) {{
				add(UUID.randomUUID().toString());
			}});
			put(StompHeaders.CONTENT_LENGTH, new ArrayList<>(1) {{
				add(getContentLength(body));
			}});
		}});

		String contentTypeString = getContentType(contentType);
		if (contentTypeString != null) {
			headers.add(StompHeaders.CONTENT_TYPE, contentTypeString);
		}

		Optional.ofNullable(userDefinedHeaders).ifPresent(headers::addAll);

		return new StompFrame(StompCommand.MESSAGE, headers, contentType == null ? null : contentType.getCharset(), body);
	}

	public static StompFrame makeReceipt(StompFrame inbound) {
		Assert.notNull(inbound, "'inbound' must not be null");

		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt == null) {
			return null;
		}

		return new StompFrame(StompCommand.RECEIPT, CollectionUtils.toMultiValueMap(Map.of(
				StompHeaders.RECEIPT_ID, Collections.singletonList(receipt)
		)), null, null);
	}

	static StompFrame makeMalformedError(StompFrame inbound, String missingHeader) {
		Assert.notNull(inbound, "'inbound' must not be null");
		Assert.notNull(missingHeader, "'missingHeader' must not be null");

		byte[] body = ("The frame:\n-----\n" + inbound + "\n-----\nDid not contain a " + missingHeader +
				" header, which is REQUIRED for frame propagation.").getBytes(StompFrame.DEFAULT_CHARSET);

		MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>() {{
			put(MESSAGE, new ArrayList<>(1) {{
				add("malformed frame received");
			}});
			put(StompHeaders.CONTENT_LENGTH, new ArrayList<>(1) {{
				add(getContentLength(body));
			}});
			put(StompHeaders.CONTENT_TYPE, new ArrayList<>(1) {{
				add(DEFAULT_CONTENT_TYPE.toString());
			}});
		}});

		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt != null) {
			headers.add(StompHeaders.RECEIPT_ID, receipt);
		}

		return new StompFrame(StompCommand.ERROR, headers, StompFrame.DEFAULT_CHARSET, body);
	}


	public static StompFrame makeError(StompFrame inbound, String errorHeader) {
		return makeError(inbound, errorHeader, null, null, null);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, String body) {
		if (body == null) {
			return makeError(inbound, errorHeader, null, null, null);
		} else {
			return makeError(inbound, errorHeader, null, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
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
		if (body == null) {
			return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), null, null);
		} else {
			return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		}
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders, String body) {
		if (body == null) {
			return makeError(inbound, errorHeader, userDefinedHeaders, null, null);
		} else {
			return makeError(inbound, errorHeader, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompFrame.DEFAULT_CHARSET));
		}
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body) {
		return makeError(inbound, errorHeader, toMultiValueMap(userDefinedHeaders), contentType, body);
	}

	public static StompFrame makeError(StompFrame inbound, String errorHeader, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body) {
		Assert.notNull(inbound, "'inbound' must not be null");
		Assert.notNull(errorHeader, "'errorHeader' must not be null");

		MultiValueMap<String, String> headers = CollectionUtils.toMultiValueMap(new HashMap<>() {{
			put(MESSAGE, new ArrayList<>(1) {{
				add(errorHeader);
			}});
			put(StompHeaders.CONTENT_LENGTH, new ArrayList<>(1) {{
				add(getContentLength(body));
			}});
		}});

		String contentTypeString = getContentType(contentType);
		if (contentTypeString != null) {
			headers.add(StompHeaders.CONTENT_TYPE, contentTypeString);
		}

		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt != null) {
			headers.add(StompHeaders.RECEIPT_ID, receipt);
		}

		Optional.ofNullable(userDefinedHeaders).ifPresent(headers::addAll);

		return new StompFrame(StompCommand.ERROR, headers, contentType == null ? null : contentType.getCharset(), body);
	}

}
