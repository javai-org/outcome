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
		// Simulate a flaky API: fails twice, then succeeds
		FlakyApi api = new FlakyApi(2, "{\"name\": \"Alice\"}");

		Retrier retrier = Retrier.builder()
				.policy(RetryPolicy.fixed(5, Duration.ZERO))
				.build();

		Outcome<String> outcome = retrier.execute(() ->
				boundary.call("UserApi.fetch", api::fetch)
		);

		assertThat(outcome.isOk()).isTrue();
		assertThat(outcome.getOrThrow()).contains("Alice");
		assertThat(api.callCount()).isEqualTo(3);  // 2 failures + 1 success
	}

	/** Simulates a service that fails N times before succeeding. */
	private static class FlakyApi {
		private final int failuresBeforeSuccess;
		private final String successResponse;
		private int calls = 0;

		FlakyApi(int failuresBeforeSuccess, String successResponse) {
			this.failuresBeforeSuccess = failuresBeforeSuccess;
			this.successResponse = successResponse;
		}

		String fetch() throws HttpConnectTimeoutException {
			calls++;
			if (calls <= failuresBeforeSuccess) {
				throw new HttpConnectTimeoutException("Timeout on call " + calls);
			}
			return successResponse;
		}

		int callCount() {
			return calls;
		}
	}
}
