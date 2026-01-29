package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.javai.outcome.Failure;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.ops.OpReporter;
import org.javai.outcome.retry.Retrier;
import org.javai.outcome.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates operator reporting during retry operations.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>{@link OpReporter} receives notifications about failures and retry attempts</li>
 *   <li>Reporters can log, emit metrics, or trigger alerts based on failure context</li>
 * </ul>
 */
public class OperatorReportingTest {

	@Test
	void reporterReceivesFailureNotificationsOnEachAttempt() {
		// Collect messages for assertions while also printing to stderr
		List<String> messages = new ArrayList<>();

		// Set up a reporter that writes failure details to stderr
		OpReporter reporter = new OpReporter() {
			@Override
			public void report(Failure failure) {
				String msg = String.format("[%s] FAILURE: %s - %s (type=%s)",
						failure.occurredAt().atZone(java.time.ZoneId.systemDefault())
								.format(DateTimeFormatter.ISO_LOCAL_TIME),
						failure.operation(),
						failure.message(),
						failure.type());
				System.err.println(msg);
				messages.add(msg);
			}

			@Override
			public void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {
				String msg = String.format("[RETRY] Attempt %d failed for %s, retrying in %dms...",
						attemptNumber,
						failure.operation(),
						delay.toMillis());
				System.err.println(msg);
				messages.add(msg);
			}

			@Override
			public void reportRetryExhausted(Failure failure, int totalAttempts) {
				String msg = String.format("[EXHAUSTED] Gave up on %s after %d attempts",
						failure.operation(),
						totalAttempts);
				System.err.println(msg);
				messages.add(msg);
			}
		};

		// Service that always returns 503
		ServiceUnavailableApi api = new ServiceUnavailableApi();

		// Use Boundary.silent() for exception conversion only.
		// The Retrier's reporter handles all failure notifications,
		// avoiding duplicate reports from both Boundary and Retrier.
		Boundary boundary = Boundary.silent();
		Retrier retrier = Retrier.builder()
				.policy(RetryPolicy.immediate(3))
				.reporter(reporter)
				.build();

		Outcome<String> outcome = retrier.execute(() ->
				boundary.call("OrderApi.submit", api::submit)
		);

		// Operation fails after exhausting retries
		assertThat(outcome.isFail()).isTrue();

		// Verify reporter received all notifications
		assertThat(messages).anyMatch(m -> m.contains("[RETRY] Attempt 1 failed"));
		assertThat(messages).anyMatch(m -> m.contains("[RETRY] Attempt 2 failed"));
		assertThat(messages).anyMatch(m -> m.contains("[EXHAUSTED] Gave up on OrderApi.submit after 3 attempts"));
	}

	/** Simulates a service returning HTTP 503 Service Unavailable. */
	private static class ServiceUnavailableApi {
		String submit() throws HttpConnectTimeoutException {
			throw new HttpConnectTimeoutException("503 Service Unavailable - server overloaded");
		}
	}
}
