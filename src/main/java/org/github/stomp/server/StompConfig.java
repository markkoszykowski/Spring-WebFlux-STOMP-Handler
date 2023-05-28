package org.github.stomp.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
class StompConfig {

	private final List<StompHandler> handlers;

	public StompConfig(List<? extends StompServer> servers) {
		handlers = servers.stream().map(StompHandler::new).toList();
	}

	@Bean
	public HandlerMapping handlerMapping() {
		return new SimpleUrlHandlerMapping(
				handlers.stream().collect(Collectors.toMap(handler -> handler.server.path(), Function.identity())),
				Ordered.HIGHEST_PRECEDENCE
		);
	}

}
