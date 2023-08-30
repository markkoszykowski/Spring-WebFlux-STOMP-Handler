package org.github.stomp.server;

import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.*;

import java.nio.charset.Charset;
import java.util.*;

public class StompUtils {

	// Header Keys
	public static final String MESSAGE = "message";
	public static final String TRANSACTION = "transaction";
	public static final String VERSION = "version";

	// Header Utils
	static final MimeType DEFAULT_CONTENT_TYPE = new MimeType(MediaType.TEXT_PLAIN, StompMessage.DEFAULT_CHARSET);
	static final String DEFAULT_CONTENT_TYPE_STRING = DEFAULT_CONTENT_TYPE.toString();

	// Helper Functions
	static String getContentLength(byte[] body) {
		return body == null ? "0" : String.valueOf(body.length);
	}

	static String getContentType(MimeType contentType) {
		return contentType == null ? null : contentType.toString();
	}


	// Make Functions
	public static StompMessage makeMessage(String destination, String subscription, String body) {
		return makeMessage(destination, subscription, null, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders) {
		return makeMessage(destination, subscription, CollectionUtils.toMultiValueMap(userDefinedHeaders), null, null);
	}

	public static StompMessage makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders) {
		return makeMessage(destination, subscription, userDefinedHeaders, null, null);
	}

	public static StompMessage makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, String body) {
		return makeMessage(destination, subscription, CollectionUtils.toMultiValueMap(userDefinedHeaders), DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, String body) {
		return makeMessage(destination, subscription, userDefinedHeaders, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeMessage(String destination, String subscription, Map<String, List<String>> userDefinedHeaders, MimeType contentType, byte[] body) {
		return makeMessage(destination, subscription, CollectionUtils.toMultiValueMap(userDefinedHeaders), contentType, body);
	}

	public static StompMessage makeMessage(String destination, String subscription, MultiValueMap<String, String> userDefinedHeaders, MimeType contentType, byte[] body) {
		Assert.notNull(destination, "Destination cannot be null");
		Assert.notNull(subscription, "Subscription cannot be null");

		Charset bodyCharset = contentType == null ? null : contentType.getCharset();
		String messageId = UUID.randomUUID().toString();
		String contentTypeString = getContentType(contentType);
		String contentLengthString = getContentLength(body);

		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>() {{
			add(StompHeaders.DESTINATION, destination);
			add(StompHeaders.SUBSCRIPTION, subscription);
			add(StompHeaders.MESSAGE_ID, messageId);
			add(StompHeaders.CONTENT_LENGTH, contentLengthString);
		}};
		if (contentTypeString != null) {
			headers.add(StompHeaders.CONTENT_TYPE, contentTypeString);
		}
		Optional.ofNullable(userDefinedHeaders).ifPresent(headers::addAll);

		return new StompMessage(StompCommand.MESSAGE, headers, bodyCharset, body);
	}

	public static StompMessage makeReceipt(StompMessage inbound) {
		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt == null) {
			return null;
		}
		return new StompMessage(StompCommand.RECEIPT, CollectionUtils.toMultiValueMap(Map.of(
				StompHeaders.RECEIPT_ID, Collections.singletonList(receipt)
		)), null, null);
	}

	static StompMessage makeMalformedError(StompMessage inbound, String missingHeader) {
		Assert.notNull(inbound, "Inbound cannot be null");
		Assert.notNull(missingHeader, "MissingHeader cannot be null");

		byte[] body = ("The frame:\n-----\n" + inbound + "\n-----\nDid not contain a " + missingHeader +
				" header, which is REQUIRED for frame propagation.").getBytes(StompMessage.DEFAULT_CHARSET);

		Charset bodyCharset = StompMessage.DEFAULT_CHARSET;
		String contentLengthString = getContentLength(body);

		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>() {{
			add(MESSAGE, "malformed frame received");
			add(StompHeaders.CONTENT_LENGTH, contentLengthString);
			add(StompHeaders.CONTENT_TYPE, DEFAULT_CONTENT_TYPE_STRING);
		}};
		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt != null) {
			headers.add(StompHeaders.RECEIPT_ID, receipt);
		}

		return new StompMessage(StompCommand.ERROR, headers, bodyCharset, body);
	}


	public static StompMessage makeError(StompMessage inbound, String errorHeader) {
		return makeError(inbound, null, errorHeader, null, null);
	}

	public static StompMessage makeError(StompMessage inbound, String errorHeader, String body) {
		return makeError(inbound, null, errorHeader, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeError(StompMessage inbound, Map<String, List<String>> userDefinedHeaders, String errorHeader) {
		return makeError(inbound, CollectionUtils.toMultiValueMap(userDefinedHeaders), errorHeader, null, null);
	}

	public static StompMessage makeError(StompMessage inbound, MultiValueMap<String, String> userDefinedHeaders, String errorHeader) {
		return makeError(inbound, userDefinedHeaders, errorHeader, null, null);
	}

	public static StompMessage makeError(StompMessage inbound, Map<String, List<String>> userDefinedHeaders, String errorHeader, String body) {
		return makeError(inbound, CollectionUtils.toMultiValueMap(userDefinedHeaders), errorHeader, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeError(StompMessage inbound, MultiValueMap<String, String> userDefinedHeaders, String errorHeader, String body) {
		return makeError(inbound, userDefinedHeaders, errorHeader, DEFAULT_CONTENT_TYPE, body.getBytes(StompMessage.DEFAULT_CHARSET));
	}

	public static StompMessage makeError(StompMessage inbound, Map<String, List<String>> userDefinedHeaders, String errorHeader, MimeType contentType, byte[] body) {
		return makeError(inbound, CollectionUtils.toMultiValueMap(userDefinedHeaders), errorHeader, contentType, body);
	}

	public static StompMessage makeError(StompMessage inbound, MultiValueMap<String, String> userDefinedHeaders, String errorHeader, MimeType contentType, byte[] body) {
		Assert.notNull(inbound, "Inbound cannot be null");
		Assert.notNull(errorHeader, "ErrorHeader cannot be null");

		Charset bodyCharset = contentType == null ? null : contentType.getCharset();
		String contentTypeString = getContentType(contentType);
		String contentLengthString = getContentLength(body);

		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>() {{
			add(MESSAGE, errorHeader);
			add(StompHeaders.CONTENT_LENGTH, contentLengthString);
		}};
		if (contentTypeString != null) {
			headers.add(StompHeaders.CONTENT_TYPE, contentTypeString);
		}
		String receipt = inbound.headers.getFirst(StompHeaders.RECEIPT);
		if (receipt != null) {
			headers.add(StompHeaders.RECEIPT_ID, receipt);
		}
		Optional.ofNullable(userDefinedHeaders).ifPresent(headers::addAll);

		return new StompMessage(StompCommand.ERROR, headers, bodyCharset, body);
	}

}
