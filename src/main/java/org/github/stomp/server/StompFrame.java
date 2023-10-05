package org.github.stomp.server;

import io.netty.handler.codec.http.HttpUtil;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
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

public class StompFrame {

	public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	static final String NULL = "\0";
	static final String NULL_STRING = "^@";
	static final String EOL = "\n";
	static final String HEADER_SEPARATOR = ":";

	static final byte[] NULL_BYTES = NULL.getBytes(DEFAULT_CHARSET);
	static final byte[] EOL_BYTES = EOL.getBytes(DEFAULT_CHARSET);
	static final byte[] HEADER_SEPARATOR_BYTES = HEADER_SEPARATOR.getBytes(DEFAULT_CHARSET);

	static final StompDecoder DECODER = new StompDecoder();

	@Getter
	@Accessors(fluent = true)
	final StompCommand command;
	final MultiValueMap<String, String> headers;
	@Getter
	@Accessors(fluent = true)
	final Charset bodyCharset;
	final byte[] body;

	String asString;
	ByteBuffer asByteBuffer;

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

		final Message<byte[]> message = DECODER.decode(byteBuffer).get(0);
		final StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

		this.command = accessor.getCommand();
		this.headers = getHeaders(accessor);
		this.bodyCharset = HttpUtil.getCharset(this.headers.getFirst(StompHeaderAccessor.STOMP_CONTENT_TYPE_HEADER), null);

		final byte[] temp = message.getPayload();
		final String contentLengthHeader = this.headers.getFirst(StompHeaderAccessor.STOMP_CONTENT_LENGTH_HEADER);
		if (contentLengthHeader != null) {
			final int contentLength = Integer.parseUnsignedInt(contentLengthHeader);
			this.body = contentLength >= temp.length ? temp : Arrays.copyOf(temp, contentLength);
		} else {
			this.body = temp;
		}

		this.asString = null;
		this.asByteBuffer = null;
	}

	public static StompFrame from(final WebSocketMessage socketMessage) {
		return new StompFrame(socketMessage);
	}

	public MultiValueMap<String, String> headers() {
		return CollectionUtils.unmodifiableMultiValueMap(this.headers);
	}

	public byte[] body() {
		return this.body != null ? this.body.clone() : null;
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

	@SuppressWarnings(value = {"unchecked"})
	static MultiValueMap<String, String> getHeaders(final StompHeaderAccessor accessor) {
		final Map<String, List<String>> headers = (Map<String, List<String>>) accessor.getHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS);
		return CollectionUtils.toMultiValueMap(headers != null ? headers : Collections.emptyMap());
	}

	int capacityGuesstimate() {
		return this.command.name().length() + (64 * this.headers.size()) + (this.body != null ? this.body.length : 0) + 4;
	}

	public String toString() {
		if (this.asString != null) {
			return this.asString;
		}

		final StringBuilder sb = new StringBuilder(this.capacityGuesstimate());

		sb.append(this.command.name()).append(EOL);

		this.headers.forEach((key, valueList) -> valueList.forEach(value -> {
			sb.append(key).append(HEADER_SEPARATOR);
			if (value != null) {
				sb.append(value);
			}
			sb.append(EOL);
		}));

		sb.append(EOL);

		if (this.body != null) {
			if (this.bodyCharset != null) {
				sb.append(new String(this.body, this.bodyCharset));
			} else {
				for (byte b : this.body) {
					sb.append(Integer.toBinaryString(b & 255 | 256).substring(1));
				}
			}
		}

		sb.append(NULL_STRING);

		return this.asString = sb.toString();
	}

	void putInBuffer(final byte[]... byteArrays) {
		for (final byte[] byteArray : byteArrays) {
			if (this.asByteBuffer.position() + byteArray.length > this.asByteBuffer.limit()) {
				int newSize = this.asByteBuffer.limit() << 1;
				if (newSize < this.asByteBuffer.limit()) {
					newSize = this.asByteBuffer.limit() + byteArray.length;
				}
				if (newSize < this.asByteBuffer.limit()) {
					throw new OutOfMemoryError();
				}

				final ByteBuffer temp = this.asByteBuffer;
				final int size = temp.position();
				temp.rewind().limit(size);

				this.asByteBuffer = ByteBuffer.allocate(newSize);
				this.asByteBuffer.put(temp);
			}
			this.asByteBuffer.put(byteArray);
		}
	}

	public ByteBuffer toByteBuffer() {
		if (this.asByteBuffer != null) {
			return this.asByteBuffer.asReadOnlyBuffer();
		}

		this.asByteBuffer = ByteBuffer.allocate(this.capacityGuesstimate());

		this.putInBuffer(this.command.name().getBytes(DEFAULT_CHARSET), EOL_BYTES);

		for (final Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
			final byte[] key = entry.getKey().getBytes(DEFAULT_CHARSET);
			for (final String value : entry.getValue()) {
				this.putInBuffer(key, HEADER_SEPARATOR_BYTES);
				if (value != null) {
					this.putInBuffer(value.getBytes(DEFAULT_CHARSET));
				}
				this.putInBuffer(EOL_BYTES);
			}
		}

		this.putInBuffer(EOL_BYTES);

		if (this.body != null) {
			this.putInBuffer(this.body);
		}

		this.putInBuffer(NULL_BYTES);

		final int size = this.asByteBuffer.position();
		this.asByteBuffer.rewind().limit(size);

		return this.asByteBuffer.asReadOnlyBuffer();
	}

	public WebSocketMessage toWebSocketMessage(final WebSocketSession session) {
		return new WebSocketMessage(WebSocketMessage.Type.TEXT, session.bufferFactory().wrap(this.toByteBuffer()));
	}

}
