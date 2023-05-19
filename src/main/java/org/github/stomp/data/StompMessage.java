package org.github.stomp.data;

import io.netty.handler.codec.http.HttpUtil;
import lombok.Builder;
import lombok.Getter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
@Builder
public class StompMessage {

	public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	private static final String NULL = "\0";
	private static final String NEWLINE = "\n";
	private static final String HEADER_SEPARATOR = ":";

	private static final byte[] NULL_BYTES = NULL.getBytes(DEFAULT_CHARSET);
	private static final byte[] NEWLINE_BYTES = NEWLINE.getBytes(DEFAULT_CHARSET);
	private static final byte[] HEADER_SEPARATOR_BYTES = HEADER_SEPARATOR.getBytes(DEFAULT_CHARSET);

	private static final StompDecoder decoder = new StompDecoder();

	private final StompCommand command;
	private final MultiValueMap<String, String> headers;
	private final Charset bodyCharset;
	private final byte[] body;

	public StompMessage(StompCommand command, Map<String, List<String>> headers) {
		this(command, CollectionUtils.toMultiValueMap(headers));
	}

	public StompMessage(StompCommand command, MultiValueMap<String, String> headers) {
		Assert.notNull(command, "Command must not be null");
		Assert.notNull(headers, "Headers must not be null");
		this.command = command;
		this.headers = CollectionUtils.unmodifiableMultiValueMap(headers);
		this.bodyCharset = null;
		this.body = null;
	}

	public StompMessage(StompCommand command, Map<String, List<String>> headers, byte[] body) {
		this(command, CollectionUtils.toMultiValueMap(headers), body);
	}

	public StompMessage(StompCommand command, MultiValueMap<String, String> headers, byte[] body) {
		Assert.notNull(command, "Command must not be null");
		Assert.notNull(headers, "Headers must not be null");
		this.command = command;
		this.headers = CollectionUtils.unmodifiableMultiValueMap(headers);
		this.bodyCharset = HttpUtil.getCharset(headers.getFirst(StompHeaders.CONTENT_TYPE), DEFAULT_CHARSET);
		this.body = body;
	}

	public StompMessage(StompCommand command, Map<String, List<String>> headers, Charset charset, byte[] body) {
		this(command, CollectionUtils.toMultiValueMap(headers), charset, body);
	}

	public StompMessage(StompCommand command, MultiValueMap<String, String> headers, Charset charset, byte[] body) {
		Assert.notNull(command, "Command must not be null");
		Assert.notNull(headers, "Headers must not be null");
		this.command = command;
		this.headers = CollectionUtils.unmodifiableMultiValueMap(headers);
		this.bodyCharset = charset;
		this.body = body;
	}

	public StompMessage(StompCommand command, Map<String, List<String>> headers, String body) {
		this(command, CollectionUtils.toMultiValueMap(headers), body);
	}

	public StompMessage(StompCommand command, MultiValueMap<String, String> headers, String body) {
		Assert.notNull(command, "Command must not be null");
		Assert.notNull(headers, "Headers must not be null");
		this.command = command;
		this.headers = CollectionUtils.unmodifiableMultiValueMap(headers);
		this.bodyCharset = DEFAULT_CHARSET;
		this.body = body == null ? null : body.getBytes(DEFAULT_CHARSET);
	}

	private StompMessage(WebSocketMessage socketMessage) {
		DataBuffer dataBuffer = socketMessage.getPayload();
		ByteBuffer byteBuffer = ByteBuffer.allocate(dataBuffer.readableByteCount());
		dataBuffer.toByteBuffer(byteBuffer);

		Message<byte[]> message = decoder.decode(byteBuffer).get(0);
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

		command = accessor.getCommand();
		headers = CollectionUtils.unmodifiableMultiValueMap(CollectionUtils.toMultiValueMap(accessor.toNativeHeaderMap()));
		int contentLength = Optional.ofNullable(headers.getFirst(StompHeaders.CONTENT_LENGTH)).map(Integer::parseUnsignedInt).orElse(-1);
		bodyCharset = HttpUtil.getCharset(headers.getFirst(StompHeaders.CONTENT_TYPE), DEFAULT_CHARSET);
		byte[] temp = message.getPayload();
		body = contentLength == -1 || contentLength >= temp.length ? temp : Arrays.copyOf(temp, contentLength);
	}

	public static StompMessage from(WebSocketMessage socketMessage) {
		return new StompMessage(socketMessage);
	}

	private static void appendBinaryRepresentation(StringBuilder sb, byte[] bytes) {
		for (byte b : bytes) {
			sb.append(Integer.toBinaryString(b & 255 | 256).substring(1));
		}
	}

	public String getCommandString() {
		return command.name();
	}

	public boolean error() {
		return StompCommand.ERROR.equals(command);
	}

	public StompMessage.StompMessageBuilder mutate() {
		return StompMessage.builder().command(command).headers(headers).bodyCharset(bodyCharset).body(body);
	}

	private int capacityGuesstimate() {
		return command.name().length() + (64 * headers.size()) + (body == null ? 0 : body.length) + 4;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder(capacityGuesstimate());
		sb.append(command.name()).append(NEWLINE);
		headers.forEach((key, valueList) -> valueList.forEach(value -> {
			sb.append(key).append(HEADER_SEPARATOR);
			Optional.ofNullable(value).ifPresent(sb::append);
			sb.append(NEWLINE);
		}));
		sb.append(NEWLINE);
		Optional.ofNullable(body).ifPresent(b -> {
			if (bodyCharset == null) {
				appendBinaryRepresentation(sb, b);
			} else {
				sb.append(new String(b, bodyCharset));
			}
		});
		sb.append(NULL);
		return sb.toString();
	}

	private ByteBuffer putInBuffer(ByteBuffer byteBuffer, byte[]... byteArrays) {
		for (byte[] byteArray : byteArrays) {
			if (byteBuffer.position() + byteArray.length > byteBuffer.limit()) {
				int newSize = byteBuffer.limit() << 1;
				if (newSize < byteBuffer.limit()) {
					newSize = byteBuffer.limit() + byteArray.length;
				}

				ByteBuffer temp = byteBuffer;
				int size = temp.position();
				temp.rewind().limit(size);

				byteBuffer = ByteBuffer.allocate(newSize);
				byteBuffer.put(temp);
			}
			byteBuffer.put(byteArray);
		}
		return byteBuffer;
	}

	public ByteBuffer toByteBuffer() {
		ByteBuffer buffer = ByteBuffer.allocate(capacityGuesstimate());
		buffer = putInBuffer(buffer, command.name().getBytes(DEFAULT_CHARSET), NEWLINE_BYTES);
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			byte[] key = entry.getKey().getBytes(DEFAULT_CHARSET);
			for (String value : entry.getValue()) {
				buffer = putInBuffer(buffer, key, HEADER_SEPARATOR_BYTES);
				if (value != null) {
					buffer = putInBuffer(buffer, value.getBytes(DEFAULT_CHARSET));
				}
				buffer = putInBuffer(buffer, NEWLINE_BYTES);
			}
		}
		buffer = putInBuffer(buffer, NEWLINE_BYTES);
		if (body != null) {
			buffer = putInBuffer(buffer, body);
		}
		buffer = putInBuffer(buffer, NULL_BYTES);

		int size = buffer.position();
		return buffer.rewind().limit(size).asReadOnlyBuffer();
	}

	public WebSocketMessage toWebSocketMessage(WebSocketSession session) {
		return new WebSocketMessage(WebSocketMessage.Type.TEXT, session.bufferFactory().wrap(toByteBuffer()));
	}

}
