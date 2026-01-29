package org.javai.outcome.examples;

import org.javai.outcome.Failure;
import org.javai.outcome.FailureId;
import org.javai.outcome.ops.metrics.MetricsOpReporter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates example MetricsOpReporter output for documentation.
 *
 * <p>Simulates a remote API call that fails twice before succeeding on the third attempt.
 */
public class MetricsOutputExample {

	@Test
	void generateMetricsOutputForApiRetryScenario() {
		List<String> output = new ArrayList<>();
		Logger capturingLogger = new PrintingLogger(output);
		MetricsOpReporter reporter = new MetricsOpReporter("orders-service", capturingLogger);

		// Create a realistic failure for a remote API timeout
		Instant baseTime = Instant.parse("2024-03-15T14:22:31.847Z");
		Failure failure = Failure.builder(
				FailureId.of("http", "connect_timeout"),
				"Connection timed out after 5000ms",
				org.javai.outcome.FailureType.TRANSIENT,
				"PaymentGateway.processPayment"
		)
				.correlationId("ord-7f3a9c2b")
				.occurredAt(baseTime)
				.build();

		// Attempt 1 fails
		reporter.report(failure);

		// About to retry (attempt 1 failed, waiting before attempt 2)
		reporter.reportRetryAttempt(failure, 1, Duration.ofMillis(200));

		// Attempt 2 fails (update timestamp)
		Failure failure2 = failure.withContext(
				failure.correlationId(),
				failure.tags()
		);
		reporter.report(failure2);

		// About to retry again (attempt 2 failed, waiting before attempt 3)
		reporter.reportRetryAttempt(failure2, 2, Duration.ofMillis(400));

		// Attempt 3 succeeds - no more reports

		// Print the captured output
		System.out.println("\n=== MetricsOpReporter JSON Output ===\n");
		for (String line : output) {
			System.out.println(line);
			System.out.println();
		}
	}

	/**
	 * Simple logger that captures and prints JSON lines.
	 */
	private static class PrintingLogger implements Logger {
		private final List<String> output;

		PrintingLogger(List<String> output) {
			this.output = output;
		}

		@Override
		public String getName() { return "example"; }

		@Override
		public boolean isInfoEnabled() { return true; }

		@Override
		public void info(String msg) {
			output.add(msg);
		}

		// Minimal implementation - only info(String) is used by MetricsOpReporter
		@Override public boolean isTraceEnabled() { return false; }
		@Override public void trace(String msg) {}
		@Override public void trace(String format, Object arg) {}
		@Override public void trace(String format, Object arg1, Object arg2) {}
		@Override public void trace(String format, Object... arguments) {}
		@Override public void trace(String msg, Throwable t) {}
		@Override public boolean isTraceEnabled(Marker marker) { return false; }
		@Override public void trace(Marker marker, String msg) {}
		@Override public void trace(Marker marker, String format, Object arg) {}
		@Override public void trace(Marker marker, String format, Object arg1, Object arg2) {}
		@Override public void trace(Marker marker, String format, Object... arguments) {}
		@Override public void trace(Marker marker, String msg, Throwable t) {}
		@Override public boolean isDebugEnabled() { return false; }
		@Override public void debug(String msg) {}
		@Override public void debug(String format, Object arg) {}
		@Override public void debug(String format, Object arg1, Object arg2) {}
		@Override public void debug(String format, Object... arguments) {}
		@Override public void debug(String msg, Throwable t) {}
		@Override public boolean isDebugEnabled(Marker marker) { return false; }
		@Override public void debug(Marker marker, String msg) {}
		@Override public void debug(Marker marker, String format, Object arg) {}
		@Override public void debug(Marker marker, String format, Object arg1, Object arg2) {}
		@Override public void debug(Marker marker, String format, Object... arguments) {}
		@Override public void debug(Marker marker, String msg, Throwable t) {}
		@Override public boolean isInfoEnabled(Marker marker) { return true; }
		@Override public void info(String format, Object arg) {}
		@Override public void info(String format, Object arg1, Object arg2) {}
		@Override public void info(String format, Object... arguments) {}
		@Override public void info(String msg, Throwable t) {}
		@Override public void info(Marker marker, String msg) {}
		@Override public void info(Marker marker, String format, Object arg) {}
		@Override public void info(Marker marker, String format, Object arg1, Object arg2) {}
		@Override public void info(Marker marker, String format, Object... arguments) {}
		@Override public void info(Marker marker, String msg, Throwable t) {}
		@Override public boolean isWarnEnabled() { return false; }
		@Override public void warn(String msg) {}
		@Override public void warn(String format, Object arg) {}
		@Override public void warn(String format, Object... arguments) {}
		@Override public void warn(String format, Object arg1, Object arg2) {}
		@Override public void warn(String msg, Throwable t) {}
		@Override public boolean isWarnEnabled(Marker marker) { return false; }
		@Override public void warn(Marker marker, String msg) {}
		@Override public void warn(Marker marker, String format, Object arg) {}
		@Override public void warn(Marker marker, String format, Object arg1, Object arg2) {}
		@Override public void warn(Marker marker, String format, Object... arguments) {}
		@Override public void warn(Marker marker, String msg, Throwable t) {}
		@Override public boolean isErrorEnabled() { return false; }
		@Override public void error(String msg) {}
		@Override public void error(String format, Object arg) {}
		@Override public void error(String format, Object arg1, Object arg2) {}
		@Override public void error(String format, Object... arguments) {}
		@Override public void error(String msg, Throwable t) {}
		@Override public boolean isErrorEnabled(Marker marker) { return false; }
		@Override public void error(Marker marker, String msg) {}
		@Override public void error(Marker marker, String format, Object arg) {}
		@Override public void error(Marker marker, String format, Object arg1, Object arg2) {}
		@Override public void error(Marker marker, String format, Object... arguments) {}
		@Override public void error(Marker marker, String msg, Throwable t) {}
	}
}
