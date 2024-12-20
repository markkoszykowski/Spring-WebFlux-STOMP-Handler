package io.github.stomp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class Server {

	private ApplicationContext context = null;

	public Server start() {
		if (this.context == null) {
			this.context = SpringApplication.run(Server.class);
		}
		return this;
	}

	public Server stop() {
		if (this.context != null) {
			if (SpringApplication.exit(this.context) != 0) {
				throw new RuntimeException("Server did not exit successfully");
			}
			this.context = null;
		}
		return this;
	}

}
