package org.github.stomp.config;

import org.github.stomp.handler.SimpleStompHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.Map;

@Configuration
public class WebSocketConfig {

	private final SimpleStompHandler handler;

	public WebSocketConfig(SimpleStompHandler handler) {
		this.handler = handler;
	}

	@Bean
	public HandlerMapping handlerMapping() {
		Map<String, WebSocketHandler> handlerMap = Map.of(
				SimpleStompHandler.WEBSOCKET_PATH, handler
		);
		return new SimpleUrlHandlerMapping(handlerMap, Ordered.HIGHEST_PRECEDENCE);
	}

}
