package io.github.stomp.server;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Accessors(chain = true, fluent = true)
public enum Endpoint {

	COUNTING(CountingServer.COUNTING_WEBSOCKET_PATH),
	HELLO_WORLD(HelloWorldServer.COUNTING_WEBSOCKET_PATH);

	@Getter
	private final String path;

}
