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

public class StompFrame {

	public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

	static final String NULL = "\0";
	static final String NULL_STRING = "^@";
	static final String EOL = "\n";
	static final String HEADER_SEPARATOR = ":";

	static final byte[] NULL_BYTES = NULL.getBytes(DEFAULT_CHARSET);
	static final byte[] EOL_BYTES = EOL.getBytes(DEFAULT_CHARSET);
	static final byte[] HEADER_SEPARATOR_BYTES = HEADER_SEPARATOR.getBytes(DEFAULT_CHARSET);

	static final ThreadLocal<StompDecoder> DECODER = ThreadLocal.withInitial(StompDecoder::new);

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


	static StompCommand parseCommand(final StompHeaderAccessor accessor) {
		final StompCommand command = accessor.getCommand();
		Assert.notNull(command, "'command' must not be null");
		return command;
	}

	@SuppressWarnings(value = {"unchecked"})
	static MultiValueMap<String, String> parseHeaders(final StompHeaderAccessor accessor) {
		final Map<String, List<String>> headers = (Map<String, List<String>>) accessor.getHeader(NativeMessageHeaderAccessor.NATIVE_HEADERS);
		return CollectionUtils.toMultiValueMap(headers != null ? headers : Collections.emptyMap());
	}

	static Charset parseBodyCharset(final StompHeaderAccessor accessor) {
		final MimeType contentType = accessor.getContentType();
		return contentType != null ? contentType.getCharset() : null;
	}

	static byte[] parseBody(final Message<byte[]> message, final StompHeaderAccessor accessor) {
		final Integer contentLength = accessor.getContentLength();
		final byte[] temp = message.getPayload();
		if (contentLength != null) {
			return contentLength >= temp.length ? temp : Arrays.copyOf(temp, contentLength);
		} else {
			return temp;
		}
	}


	int capacityGuesstimate() {
		return this.command.name().length() + (64 * this.headers.size()) + (this.body != null ? this.body.length : 0) + 4;
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
			if (this.bodyCharset != null) {
				sb.append(new String(this.body, this.bodyCharset));
			} else {
				for (final byte b : this.body) {
					sb.append(Integer.toBinaryString(b & 255 | 256).substring(1));
				}
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

		index = this.putInBuffer(index, commandBytes(this.command));
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

	static final byte[] STOMP_BYTES = StompCommand.STOMP.name().getBytes(DEFAULT_CHARSET);
	static final byte[] CONNECT_BYTES = StompCommand.CONNECT.name().getBytes(DEFAULT_CHARSET);
	static final byte[] DISCONNECT_BYTES = StompCommand.DISCONNECT.name().getBytes(DEFAULT_CHARSET);
	static final byte[] SUBSCRIBE_BYTES = StompCommand.SUBSCRIBE.name().getBytes(DEFAULT_CHARSET);
	static final byte[] UNSUBSCRIBE_BYTES = StompCommand.UNSUBSCRIBE.name().getBytes(DEFAULT_CHARSET);
	static final byte[] SEND_BYTES = StompCommand.SEND.name().getBytes(DEFAULT_CHARSET);
	static final byte[] ACK_BYTES = StompCommand.ACK.name().getBytes(DEFAULT_CHARSET);
	static final byte[] NACK_BYTES = StompCommand.NACK.name().getBytes(DEFAULT_CHARSET);
	static final byte[] BEGIN_BYTES = StompCommand.BEGIN.name().getBytes(DEFAULT_CHARSET);
	static final byte[] COMMIT_BYTES = StompCommand.COMMIT.name().getBytes(DEFAULT_CHARSET);
	static final byte[] ABORT_BYTES = StompCommand.ABORT.name().getBytes(DEFAULT_CHARSET);
	static final byte[] CONNECTED_BYTES = StompCommand.CONNECTED.name().getBytes(DEFAULT_CHARSET);
	static final byte[] RECEIPT_BYTES = StompCommand.RECEIPT.name().getBytes(DEFAULT_CHARSET);
	static final byte[] MESSAGE_BYTES = StompCommand.MESSAGE.name().getBytes(DEFAULT_CHARSET);
	static final byte[] ERROR_BYTES = StompCommand.ERROR.name().getBytes(DEFAULT_CHARSET);

	static byte[] commandBytes(final StompCommand command) {
		return switch (command) {
			case StompCommand.STOMP -> STOMP_BYTES;
			case StompCommand.CONNECT -> CONNECT_BYTES;
			case StompCommand.DISCONNECT -> DISCONNECT_BYTES;
			case StompCommand.SUBSCRIBE -> SUBSCRIBE_BYTES;
			case StompCommand.UNSUBSCRIBE -> UNSUBSCRIBE_BYTES;
			case StompCommand.SEND -> SEND_BYTES;
			case StompCommand.ACK -> ACK_BYTES;
			case StompCommand.NACK -> NACK_BYTES;
			case StompCommand.BEGIN -> BEGIN_BYTES;
			case StompCommand.COMMIT -> COMMIT_BYTES;
			case StompCommand.ABORT -> ABORT_BYTES;
			case StompCommand.CONNECTED -> CONNECTED_BYTES;
			case StompCommand.RECEIPT -> RECEIPT_BYTES;
			case StompCommand.MESSAGE -> MESSAGE_BYTES;
			case StompCommand.ERROR -> ERROR_BYTES;
		};
	}

}
