package org.github.stomp.server;

import io.netty.handler.codec.http.HttpUtil;
import lombok.Builder;
import lombok.Getter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
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
public class StompFrame {

	public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	static final String NULL = "\0";
	static final String NULL_STRING = "^@";
	static final String EOL = "\n";
	static final String HEADER_SEPARATOR = ":";

	static final byte[] NULL_BYTES = NULL.getBytes(DEFAULT_CHARSET);
	static final byte[] EOL_BYTES = EOL.getBytes(DEFAULT_CHARSET);
	static final byte[] HEADER_SEPARATOR_BYTES = HEADER_SEPARATOR.getBytes(DEFAULT_CHARSET);

	static final StompDecoder decoder = new StompDecoder();

	final StompCommand command;
	final MultiValueMap<String, String> headers;
	final Charset bodyCharset;
	final byte[] body;

	StompFrame(StompCommand command, MultiValueMap<String, String> headers, Charset charset, byte[] body) {
		Assert.notNull(command, "'command' must not be null");
		Assert.notNull(headers, "'headers' must not be null");

		this.command = command;
		this.headers = headers;
		this.bodyCharset = charset;
		this.body = body;
	}

	StompFrame(WebSocketMessage webSocketMessage) {
		Assert.notNull(webSocketMessage, "'webSocketMessage' must not be null");

		DataBuffer dataBuffer = webSocketMessage.getPayload();
		ByteBuffer byteBuffer = ByteBuffer.allocate(dataBuffer.readableByteCount());
		dataBuffer.toByteBuffer(byteBuffer);

		Message<byte[]> message = decoder.decode(byteBuffer).get(0);
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

		this.command = accessor.getCommand();
		this.headers = CollectionUtils.toMultiValueMap(accessor.toNativeHeaderMap());
		this.bodyCharset = HttpUtil.getCharset(this.headers.getFirst(StompHeaderAccessor.STOMP_CONTENT_TYPE_HEADER), null);

		int contentLength = Optional.ofNullable(this.headers.getFirst(StompHeaderAccessor.STOMP_CONTENT_LENGTH_HEADER))
				.map(Integer::parseUnsignedInt)
				.orElse(-1);
		byte[] temp = message.getPayload();
		this.body = contentLength == -1 || contentLength >= temp.length ? temp : Arrays.copyOf(temp, contentLength);
	}

	public static StompFrame from(WebSocketMessage socketMessage) {
		return new StompFrame(socketMessage);
	}

	public MultiValueMap<String, String> getHeaders() {
		return CollectionUtils.unmodifiableMultiValueMap(this.headers);
	}

	public byte[] getBody() {
		return this.body == null ? null : body.clone();
	}

	public String getCommandString() {
		return this.command.name();
	}

	public boolean error() {
		return this.command == StompCommand.ERROR;
	}

	public StompFrame.StompFrameBuilder mutate() {
		return StompFrame.builder()
				.command(this.command)
				.headers(this.headers)
				.bodyCharset(this.bodyCharset)
				.body(this.body);
	}

	static void appendBinaryRepresentation(StringBuilder sb, byte[] bytes) {
		for (byte b : bytes) {
			sb.append(Integer.toBinaryString(b & 255 | 256).substring(1));
		}
	}

	int capacityGuesstimate() {
		return this.command.name().length() + (64 * this.headers.size()) + (this.body == null ? 0 : this.body.length) + 4;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder(this.capacityGuesstimate());

		sb.append(this.command.name()).append(EOL);

		this.headers.forEach((key, valueList) -> valueList.forEach(value -> {
			sb.append(key).append(HEADER_SEPARATOR);
			Optional.ofNullable(value).ifPresent(sb::append);
			sb.append(EOL);
		}));

		sb.append(EOL);

		Optional.ofNullable(this.body).ifPresent(b -> {
			if (this.bodyCharset == null) {
				appendBinaryRepresentation(sb, b);
			} else {
				sb.append(new String(b, this.bodyCharset));
			}
		});

		sb.append(NULL_STRING);

		return sb.toString();
	}

	static ByteBuffer putInBuffer(ByteBuffer byteBuffer, byte[]... byteArrays) {
		for (byte[] byteArray : byteArrays) {
			if (byteBuffer.position() + byteArray.length > byteBuffer.limit()) {
				int newSize = byteBuffer.limit() << 1;
				if (newSize < byteBuffer.limit()) {
					newSize = byteBuffer.limit() + byteArray.length;
				}
				if (newSize < byteBuffer.limit()) {
					throw new OutOfMemoryError();
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
		ByteBuffer buffer = ByteBuffer.allocate(this.capacityGuesstimate());

		buffer = putInBuffer(buffer, this.command.name().getBytes(DEFAULT_CHARSET), EOL_BYTES);

		for (Map.Entry<String, List<String>> entry : this.headers.entrySet()) {
			byte[] key = entry.getKey().getBytes(DEFAULT_CHARSET);
			for (String value : entry.getValue()) {
				buffer = putInBuffer(buffer, key, HEADER_SEPARATOR_BYTES);
				if (value != null) {
					buffer = putInBuffer(buffer, value.getBytes(DEFAULT_CHARSET));
				}
				buffer = putInBuffer(buffer, EOL_BYTES);
			}
		}

		buffer = putInBuffer(buffer, EOL_BYTES);

		if (this.body != null) {
			buffer = putInBuffer(buffer, this.body);
		}

		buffer = putInBuffer(buffer, NULL_BYTES);

		int size = buffer.position();
		return buffer.rewind().limit(size).asReadOnlyBuffer();
	}

	public WebSocketMessage toWebSocketMessage(WebSocketSession session) {
		return new WebSocketMessage(WebSocketMessage.Type.TEXT, session.bufferFactory().wrap(this.toByteBuffer()));
	}

}
