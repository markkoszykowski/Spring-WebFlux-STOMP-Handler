package io.github.stomp;

import io.cucumber.java.en.And;
import io.cucumber.java.en.When;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class StepsDefinition {

	private ApplicationContext context;

	@When(value = "the STOMP server is up")
	public void startServer() {
		if (this.context == null) {
			this.context = SpringApplication.run(StepsDefinition.class);
		}
	}

	@When(value = "the STOMP server is down")
	public void stopServer() {
		if (this.context != null) {
			SpringApplication.exit(this.context);
			this.context = null;
		}
	}

	@And(value = "block")
	public void block() {
		final IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(1_000L);
		while (true) {
			idleStrategy.idle();
		}
	}

}
