Feature: STOMP WebFlux Server

  Background:
    Given the STOMP server is up

    Scenario: Bring down server
      Then the STOMP server is down
