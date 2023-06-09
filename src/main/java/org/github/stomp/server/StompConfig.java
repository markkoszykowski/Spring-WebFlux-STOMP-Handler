package org.github.stomp.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
class StompConfig {

	final List<StompHandler> handlers;

	StompConfig(List<? extends StompServer> servers) {
		handlers = servers.stream().map(StompHandler::new).toList();
	}

	@Bean
	HandlerMapping handlerMapping() {
		Map<String, StompHandler> handlerMap = handlers.stream().collect(Collectors.toMap(handler -> handler.server.path(), Function.identity()));
		Assert.isTrue(handlerMap.size() == handlers.size(), "Paths of StompServers should be unique");
		return new SimpleUrlHandlerMapping(handlerMap, Ordered.HIGHEST_PRECEDENCE);
	}

}
