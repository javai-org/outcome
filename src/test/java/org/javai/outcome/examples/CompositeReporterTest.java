package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.javai.outcome.Failure;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.javai.outcome.ops.OpReporter;
import org.javai.outcome.ops.log4j.Log4jOpReporter;
import org.javai.outcome.retry.Retrier;
import org.javai.outcome.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates composing multiple OpReporters together.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>{@link OpReporter#composite} combines multiple reporters</li>
 *   <li>Each reporter receives all failure notifications</li>
 *   <li>Useful for sending failures to logs, metrics, and alerts simultaneously</li>
 * </ul>
 */
public class CompositeReporterTest {

	@Test
	void compositeReporterFansOutToAllReporters() {
		// Reporter 1: Structured logging via SLF4J
		Log4jOpReporter logReporter = new Log4jOpReporter();

		// Reporter 2: Collect failures for assertions (or metrics, alerts, etc.)
		List<Failure> collectedFailures = new ArrayList<>();
		OpReporter collectingReporter = failure -> collectedFailures.add(failure);

		// Combine reporters - both receive all notifications
		OpReporter compositeReporter = OpReporter.composite(logReporter, collectingReporter);

		// Service that always fails
		ServiceUnavailableApi api = new ServiceUnavailableApi();

		// Wire up with the composite reporter
		Boundary boundary = Boundary.silent();
		Retrier retrier = Retrier.builder()
				.policy(RetryPolicy.immediate(3))
				.reporter(compositeReporter)
				.build();

		Outcome<String> outcome = retrier.execute(() ->
				boundary.call("OrderApi.submit", api::submit)
		);

		assertThat(outcome.isFail()).isTrue();

		// The collecting reporter received all 3 failures
		assertThat(collectedFailures).hasSize(3);
		assertThat(collectedFailures).allMatch(f ->
				f.operation().equals("OrderApi.submit"));
	}

	/** Simulates a service returning HTTP 503 Service Unavailable. */
	private static class ServiceUnavailableApi {
		String submit() throws HttpConnectTimeoutException {
			throw new HttpConnectTimeoutException("503 Service Unavailable");
		}
	}
}
