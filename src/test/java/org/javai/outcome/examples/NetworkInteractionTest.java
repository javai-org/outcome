package org.javai.outcome.examples;

import org.javai.outcome.FailureType;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.retry.Retrier;
import org.javai.outcome.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates using Boundary and Retrier for network operations.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>{@link Boundary} converts exceptions into {@link Outcome} values</li>
 *   <li>{@link Retrier} automatically retries transient failures</li>
 * </ul>
 */
public class NetworkInteractionTest {

	private final Boundary boundary = Boundary.silent();

	@Test
	void boundaryConvertsExceptionToOutcome() {
		// Boundary wraps a throwing operation, converting exceptions to Outcome.Fail
		Outcome<String> outcome = boundary.call("UserApi.fetch", () -> {
			throw new HttpConnectTimeoutException("Connection timed out");
		});

		// No exception thrown - failure is captured as a value
		assertThat(outcome.isFail()).isTrue();

		// Failure details are available for inspection
		var failure = ((Outcome.Fail<String>) outcome).failure();
		assertThat(failure.type()).isEqualTo(FailureType.TRANSIENT);
		assertThat(failure.message()).contains("timed out");
	}

	@Test
	void retrierAutomaticallyRetriesTransientFailures() {
		Retrier retrier = Retrier.builder()
				.policy(RetryPolicy.fixed(5, Duration.ZERO))  // Up to 5 attempts, no delay
				.build();

		var attempts = new int[]{0};

		// Retrier wraps an Outcome-returning operation and retries on transient failures
		Outcome<String> outcome = retrier.execute(() ->
				boundary.call("UserApi.fetch", () -> {
					attempts[0]++;
					if (attempts[0] < 3) {
						throw new HttpConnectTimeoutException("Timeout on attempt " + attempts[0]);
					}
					return "{\"name\": \"Alice\"}";
				})
		);

		// Succeeds on third attempt
		assertThat(outcome.isOk()).isTrue();
		assertThat(outcome.getOrThrow()).contains("Alice");
		assertThat(attempts[0]).isEqualTo(3);
	}
}
