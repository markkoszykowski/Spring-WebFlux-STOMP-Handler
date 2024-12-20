package io.github.stomp;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.agrona.ExpandableDirectByteBuffer;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class StompFrame {

	public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	static final String NULL = "\0";
	static final String NULL_STRING = "^@";
	static final String EOL = "\n";
	static final String HEADER_SEPARATOR = ":";

	static final byte[] NULL_BYTES = NULL.getBytes(DEFAULT_CHARSET);
	static final byte[] EOL_BYTES = EOL.getBytes(DEFAULT_CHARSET);
	static final byte[] HEADER_SEPARATOR_BYTES = HEADER_SEPARATOR.getBytes(DEFAULT_CHARSET);

	static final ThreadLocal<StompDecoder> DECODER = ThreadLocal.withInitial(StompDecoder::new);

	static final byte[][] COMMAND_BYTES = new byte[StompCommand.values().length][];

	static {
		for (final StompCommand command : StompCommand.values()) {
			COMMAND_BYTES[command.ordinal()] = command.name().getBytes(DEFAULT_CHARSET);
		}
	}

	@Getter
	@Accessors(fluent = true)
	final StompCommand command;
	final MultiValueMap<String, String> headers;
	MultiValueMap<String, String> immutableHeaders;
	@Getter
	@Accessors(fluent = true)
	final Charset bodyCharset;
	final byte[] body;

	String asString;
	ExpandableDirectByteBuffer asByteBuffer;

	@Builder
	StompFrame(final StompCommand command, final MultiValueMap<String, String> headers, final Charset bodyCharset, final byte[] body) {
		Assert.notNull(command, "'command' must not be null");
		Assert.notNull(headers, "'headers' must not be null");

		this.command = command;
		this.headers = headers;
		this.bodyCharset = bodyCharset;
		this.body = body;

		this.asString = null;
		this.asByteBuffer = null;
	}

	StompFrame(final WebSocketMessage webSocketMessage) {
		Assert.notNull(webSocketMessage, "'webSocketMessage' must not be null");

		final DataBuffer dataBuffer = webSocketMessage.getPayload();
		final ByteBuffer byteBuffer = ByteBuffer.allocate(dataBuffer.readableByteCount());
		dataBuffer.toByteBuffer(byteBuffer);

		final Message<byte[]> message = DECODER.get().decode(byteBuffer).getFirst();
		final StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

		this.command = parseCommand(accessor);
		this.headers = parseHeaders(accessor);
		this.bodyCharset = parseBodyCharset(accessor);
		this.body = parseBody(message, accessor);

		this.asString = null;
		this.asByteBuffer = null;
	}

	public static StompFrame from(final WebSocketMessage socketMessage) {
		return new StompFrame(socketMessage);
	}

	public MultiValueMap<String, String> headers() {
		if (this.immutableHeaders == null) {
			this.immutableHeaders = CollectionUtils.unmodifiableMultiValueMap(this.headers);
		}
		return this.immutableHeaders;
	}

	public byte[] body() {
		return this.body == null ? null : this.body.clone();
	}

	public String commandString() {
		return this.command.name();
	}

	public StompFrame.StompFrameBuilder mutate() {
		return StompFrame.builder()
				.command(this.command)
				.headers(this.headers)
				.bodyCharset(this.bodyCharset)
				.body(this.body);
	}


	static StompCommand parseCommand(final StompHeaderAccessor accessor) {
		final StompCommand command = accessor.getCommand();
		Assert.notNull(command, "'command' must not be null");
		return command;
	}

	@SuppressWarnings(value = {"unchecked"})
	static MultiValueMap<String, String> parseHeaders(final StompHeaderAccessor accessor) {
		final Map<String, List<String>> headers = (Map<String, List<String>>) accessor.getHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS);
		return CollectionUtils.toMultiValueMap(headers == null ? Collections.emptyMap() : headers);
	}

	static Charset parseBodyCharset(final StompHeaderAccessor accessor) {
		final MimeType contentType = accessor.getContentType();
		return contentType == null ? null : contentType.getCharset();
	}

	static byte[] parseBody(final Message<byte[]> message, final StompHeaderAccessor accessor) {
		final Integer contentLength = accessor.getContentLength();
		final byte[] temp = message.getPayload();
		if (contentLength == null) {
			return temp;
		} else {
			return contentLength < temp.length ? Arrays.copyOf(temp, contentLength) : temp;
		}
	}


	int capacityGuesstimate() {
		return this.command.name().length() + (64 * this.headers.size()) + (this.body == null ? 0 : this.body.length) + 4;
	}

	@Override
	public String toString() {
		if (this.asString != null) {
			return this.asString;
		}

		final StringBuilder sb = new StringBuilder(this.capacityGuesstimate());

		sb.append(this.command.name()).append(EOL);

		for (final Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
			for (final String value : entry.getValue()) {
				sb.append(entry.getKey()).append(HEADER_SEPARATOR);
				if (value != null) {
					sb.append(value);
				}
				sb.append(EOL);
			}
		}

		sb.append(EOL);

		if (this.body != null) {
			if (this.bodyCharset == null) {
				for (final byte b : this.body) {
					for (int i = Byte.SIZE - 1; 0 <= i; --i) {
						sb.append(((b >>> i) & 0b1) == 0 ? '0' : '1');
					}
				}
			} else {
				sb.append(new String(this.body, this.bodyCharset));
			}
		}

		sb.append(NULL_STRING);

		return this.asString = sb.toString();
	}

	int putInBuffer(final int index, final byte[] bytes) {
		this.asByteBuffer.putBytes(index, bytes);
		return index + bytes.length;
	}

	public ByteBuffer toByteBuffer() {
		if (this.asByteBuffer != null) {
			return this.asByteBuffer.byteBuffer().asReadOnlyBuffer();
		}

		int index = 0;
		this.asByteBuffer = new ExpandableDirectByteBuffer(this.capacityGuesstimate());

		index = this.putInBuffer(index, COMMAND_BYTES[this.command.ordinal()]);
		index = this.putInBuffer(index, EOL_BYTES);

		for (final Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
			final byte[] key = entry.getKey().getBytes(DEFAULT_CHARSET);
			for (final String value : entry.getValue()) {
				index = this.putInBuffer(index, key);
				index = this.putInBuffer(index, HEADER_SEPARATOR_BYTES);
				if (value != null) {
					index = this.putInBuffer(index, value.getBytes(DEFAULT_CHARSET));
				}
				index = this.putInBuffer(index, EOL_BYTES);
			}
		}

		index = this.putInBuffer(index, EOL_BYTES);

		if (this.body != null) {
			index = this.putInBuffer(index, this.body);
		}

		index = this.putInBuffer(index, NULL_BYTES);
		this.asByteBuffer.byteBuffer().clear().position(0).limit(index);

		return this.asByteBuffer.byteBuffer().asReadOnlyBuffer();
	}

	static Function<StompFrame, WebSocketMessage> toWebSocketMessage(final WebSocketSession session) {
		return frame -> new WebSocketMessage(WebSocketMessage.Type.TEXT, session.bufferFactory().wrap(frame.toByteBuffer()));
	}

}
