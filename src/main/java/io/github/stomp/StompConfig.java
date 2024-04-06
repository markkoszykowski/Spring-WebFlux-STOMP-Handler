package io.github.stomp;

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

	final Map<String, StompHandler> handlerMap;

	StompConfig(final List<? extends StompServer> servers) {
		this.handlerMap = servers.stream()
				.map(StompHandler::new)
				.collect(Collectors.toMap(handler -> handler.server.path(), Function.identity()));
		Assert.isTrue(this.handlerMap.size() == servers.size(), "Paths of StompServers should be unique");
	}

	@Bean
	HandlerMapping handlerMapping() {
		return new SimpleUrlHandlerMapping(this.handlerMap, Ordered.HIGHEST_PRECEDENCE);
	}

}
