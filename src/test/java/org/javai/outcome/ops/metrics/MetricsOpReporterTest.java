package org.javai.outcome.ops.metrics;

import org.javai.outcome.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MetricsOpReporterTest {

	private List<String> capturedMessages;
	private Logger capturingLogger;
	private MetricsOpReporter reporter;

	@BeforeEach
	void setUp() {
		capturedMessages = new ArrayList<>();
		capturingLogger = new CapturingLogger(capturedMessages);
		reporter = new MetricsOpReporter(null, capturingLogger);
	}

	@Test
	void report_emitsFailureEventAsJsonLine() {
		Failure failure = createFailure("test.operation", "http:timeout", "Connection timed out");

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).startsWith("{");
		assertThat(json).endsWith("}");
		assertThat(json).contains("\"eventType\":\"failure\"");
		assertThat(json).contains("\"trackingKey\":\"test.operation\"");
		assertThat(json).contains("\"code\":\"http:timeout\"");
		assertThat(json).contains("\"operation\":\"test.operation\"");
	}

	@Test
	void report_withNamespace_prependsToTrackingKey() {
		MetricsOpReporter reporterWithNamespace = new MetricsOpReporter("myapp", capturingLogger);
		Failure failure = createFailure("order.fetch", "http:timeout", "Connection timed out");

		reporterWithNamespace.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"trackingKey\":\"myapp.order.fetch\"");
	}

	@Test
	void report_withoutNamespace_usesTrackingIdOnly() {
		MetricsOpReporter reporterNoNamespace = new MetricsOpReporter(null, capturingLogger);
		Failure failure = createFailure("order.fetch", "http:timeout", "Connection timed out");

		reporterNoNamespace.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"trackingKey\":\"order.fetch\"");
	}

	@Test
	void report_withEmptyNamespace_usesTrackingIdOnly() {
		MetricsOpReporter reporterEmptyNamespace = new MetricsOpReporter("  ", capturingLogger);
		Failure failure = createFailure("order.fetch", "http:timeout", "Connection timed out");

		reporterEmptyNamespace.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"trackingKey\":\"order.fetch\"");
	}

	@Test
	void report_withCustomTrackingId_usesCustomId() {
		Failure failure = Failure.builder(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				FailureType.TRANSIENT,
				"OrdersApi.fetchOrder"
		)
				.trackingId("orders.fetch.api")
				.build();

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"trackingKey\":\"orders.fetch.api\"");
	}

	@Test
	void report_defaultTrackingId_usesOperation() {
		Failure failure = Failure.transientFailure(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				"OrdersApi.fetchOrder",
				null
		);

		assertThat(failure.trackingId()).isEqualTo("OrdersApi.fetchOrder");

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"trackingKey\":\"OrdersApi.fetchOrder\"");
	}

	@Test
	void report_escapesJsonSpecialCharacters() {
		Failure failure = Failure.builder(
				FailureId.of("parse", "invalid"),
				"Invalid \"json\" with\nnewlines\tand\rtabs",
				FailureType.TRANSIENT,
				"Parser.parse"
		)
				.trackingId("parser\\test")
				.build();

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		// Verify escaping is correct
		assertThat(json).contains("\\\"json\\\"");
		assertThat(json).contains("\\n");
		assertThat(json).contains("\\t");
		assertThat(json).contains("\\r");
		assertThat(json).contains("parser\\\\test");
	}

	@Test
	void report_handlesNullCorrelationId() {
		Failure failure = Failure.builder(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				FailureType.TRANSIENT,
				"test.operation"
		)
				.correlationId(null)
				.build();

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).doesNotContain("correlationId");
	}

	@Test
	void report_includesCorrelationIdWhenPresent() {
		Failure failure = Failure.builder(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				FailureType.TRANSIENT,
				"test.operation"
		)
				.correlationId("trace-123")
				.build();

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"correlationId\":\"trace-123\"");
	}

	@Test
	void report_includesTags() {
		Failure failure = Failure.builder(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				FailureType.TRANSIENT,
				"test.operation"
		)
				.tags(Map.of("service", "orders", "region", "us-east-1"))
				.build();

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"tags\":{");
		assertThat(json).contains("\"service\":\"orders\"");
		assertThat(json).contains("\"region\":\"us-east-1\"");
	}

	@Test
	void report_includesTypeAndNotification() {
		// transientFailure creates TRANSIENT type which maps to OBSERVE notification
		Failure failure = Failure.transientFailure(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				"test.operation",
				null
		);

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"type\":\"TRANSIENT\"");
		assertThat(json).contains("\"notification\":\"OBSERVE\"");
	}

	@Test
	void report_includesTimestamp() {
		Instant fixedTime = Instant.parse("2024-01-20T10:30:00Z");
		Failure failure = Failure.builder(
				FailureId.of("http", "timeout"),
				"Connection timed out",
				FailureType.TRANSIENT,
				"test.operation"
		)
				.occurredAt(fixedTime)
				.build();

		reporter.report(failure);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"timestamp\":\"2024-01-20T10:30:00Z\"");
	}

	@Test
	void reportRetryAttempt_emitsRetryAttemptEvent() {
		Failure failure = createFailure("test.operation", "http:timeout", "Connection timed out");

		reporter.reportRetryAttempt(failure, 3, Duration.ofMillis(500));

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"eventType\":\"retry_attempt\"");
		assertThat(json).contains("\"attemptNumber\":\"3\"");
		assertThat(json).contains("\"delayMs\":\"500\"");
		assertThat(json).contains("\"trackingKey\":\"test.operation\"");
	}

	@Test
	void reportRetryExhausted_emitsRetryExhaustedEvent() {
		Failure failure = createFailure("test.operation", "http:timeout", "Connection timed out");

		reporter.reportRetryExhausted(failure, 5);

		assertThat(capturedMessages).hasSize(1);
		String json = capturedMessages.getFirst();
		assertThat(json).contains("\"eventType\":\"retry_exhausted\"");
		assertThat(json).contains("\"totalAttempts\":\"5\"");
		assertThat(json).contains("\"trackingKey\":\"test.operation\"");
	}

	@Test
	void report_catchesExceptions_doesNotThrow() {
		Logger throwingLogger = new ThrowingLogger();
		MetricsOpReporter reporterWithThrowingLogger = new MetricsOpReporter(null, throwingLogger);

		Failure failure = createFailure("test.operation", "http:timeout", "Connection timed out");

		// Should not throw
		assertThatCode(() -> reporterWithThrowingLogger.report(failure))
				.doesNotThrowAnyException();
	}

	@Test
	void buildTrackingKey_withNamespace() {
		MetricsOpReporter reporterWithNamespace = new MetricsOpReporter("myapp", capturingLogger);
		Failure failure = createFailure("order.fetch", "http:timeout", "Connection timed out");

		String trackingKey = reporterWithNamespace.buildTrackingKey(failure);

		assertThat(trackingKey).isEqualTo("myapp.order.fetch");
	}

	@Test
	void buildTrackingKey_withoutNamespace() {
		Failure failure = createFailure("order.fetch", "http:timeout", "Connection timed out");

		String trackingKey = reporter.buildTrackingKey(failure);

		assertThat(trackingKey).isEqualTo("order.fetch");
	}

	@Test
	void escapeJson_handlesAllSpecialCharacters() {
		assertThat(MetricsOpReporter.escapeJson("hello\\world")).isEqualTo("hello\\\\world");
		assertThat(MetricsOpReporter.escapeJson("hello\"world")).isEqualTo("hello\\\"world");
		assertThat(MetricsOpReporter.escapeJson("hello\nworld")).isEqualTo("hello\\nworld");
		assertThat(MetricsOpReporter.escapeJson("hello\rworld")).isEqualTo("hello\\rworld");
		assertThat(MetricsOpReporter.escapeJson("hello\tworld")).isEqualTo("hello\\tworld");
	}

	@Test
	void escapeJson_handlesNull() {
		assertThat(MetricsOpReporter.escapeJson(null)).isEqualTo("");
	}

	@Test
	void constructors_defaultLoggerName() {
		// Verify constructors don't throw
		assertThatCode(() -> new MetricsOpReporter()).doesNotThrowAnyException();
		assertThatCode(() -> new MetricsOpReporter("myapp")).doesNotThrowAnyException();
		assertThatCode(() -> new MetricsOpReporter("myapp", "custom.logger")).doesNotThrowAnyException();
	}

	private Failure createFailure(String operation, String code, String message) {
		String[] parts = code.split(":");
		FailureId failureId = parts.length == 2
				? FailureId.of(parts[0], parts[1])
				: FailureId.of("unknown", code);
		return Failure.transientFailure(failureId, message, operation, null);
	}

	/**
	 * A simple SLF4J Logger implementation that captures info messages for testing.
	 */
	private static class CapturingLogger implements Logger {
		private final List<String> messages;

		CapturingLogger(List<String> messages) {
			this.messages = messages;
		}

		@Override
		public String getName() { return "test"; }

		@Override
		public boolean isTraceEnabled() { return false; }

		@Override
		public void trace(String msg) {}

		@Override
		public void trace(String format, Object arg) {}

		@Override
		public void trace(String format, Object arg1, Object arg2) {}

		@Override
		public void trace(String format, Object... arguments) {}

		@Override
		public void trace(String msg, Throwable t) {}

		@Override
		public boolean isTraceEnabled(Marker marker) { return false; }

		@Override
		public void trace(Marker marker, String msg) {}

		@Override
		public void trace(Marker marker, String format, Object arg) {}

		@Override
		public void trace(Marker marker, String format, Object arg1, Object arg2) {}

		@Override
		public void trace(Marker marker, String format, Object... arguments) {}

		@Override
		public void trace(Marker marker, String msg, Throwable t) {}

		@Override
		public boolean isDebugEnabled() { return false; }

		@Override
		public void debug(String msg) {}

		@Override
		public void debug(String format, Object arg) {}

		@Override
		public void debug(String format, Object arg1, Object arg2) {}

		@Override
		public void debug(String format, Object... arguments) {}

		@Override
		public void debug(String msg, Throwable t) {}

		@Override
		public boolean isDebugEnabled(Marker marker) { return false; }

		@Override
		public void debug(Marker marker, String msg) {}

		@Override
		public void debug(Marker marker, String format, Object arg) {}

		@Override
		public void debug(Marker marker, String format, Object arg1, Object arg2) {}

		@Override
		public void debug(Marker marker, String format, Object... arguments) {}

		@Override
		public void debug(Marker marker, String msg, Throwable t) {}

		@Override
		public boolean isInfoEnabled() { return true; }

		@Override
		public void info(String msg) {
			messages.add(msg);
		}

		@Override
		public void info(String format, Object arg) {}

		@Override
		public void info(String format, Object arg1, Object arg2) {}

		@Override
		public void info(String format, Object... arguments) {}

		@Override
		public void info(String msg, Throwable t) {}

		@Override
		public boolean isInfoEnabled(Marker marker) { return true; }

		@Override
		public void info(Marker marker, String msg) {}

		@Override
		public void info(Marker marker, String format, Object arg) {}

		@Override
		public void info(Marker marker, String format, Object arg1, Object arg2) {}

		@Override
		public void info(Marker marker, String format, Object... arguments) {}

		@Override
		public void info(Marker marker, String msg, Throwable t) {}

		@Override
		public boolean isWarnEnabled() { return false; }

		@Override
		public void warn(String msg) {}

		@Override
		public void warn(String format, Object arg) {}

		@Override
		public void warn(String format, Object... arguments) {}

		@Override
		public void warn(String format, Object arg1, Object arg2) {}

		@Override
		public void warn(String msg, Throwable t) {}

		@Override
		public boolean isWarnEnabled(Marker marker) { return false; }

		@Override
		public void warn(Marker marker, String msg) {}

		@Override
		public void warn(Marker marker, String format, Object arg) {}

		@Override
		public void warn(Marker marker, String format, Object arg1, Object arg2) {}

		@Override
		public void warn(Marker marker, String format, Object... arguments) {}

		@Override
		public void warn(Marker marker, String msg, Throwable t) {}

		@Override
		public boolean isErrorEnabled() { return false; }

		@Override
		public void error(String msg) {}

		@Override
		public void error(String format, Object arg) {}

		@Override
		public void error(String format, Object arg1, Object arg2) {}

		@Override
		public void error(String format, Object... arguments) {}

		@Override
		public void error(String msg, Throwable t) {}

		@Override
		public boolean isErrorEnabled(Marker marker) { return false; }

		@Override
		public void error(Marker marker, String msg) {}

		@Override
		public void error(Marker marker, String format, Object arg) {}

		@Override
		public void error(Marker marker, String format, Object arg1, Object arg2) {}

		@Override
		public void error(Marker marker, String format, Object... arguments) {}

		@Override
		public void error(Marker marker, String msg, Throwable t) {}
	}

	/**
	 * A logger that throws exceptions for testing exception handling.
	 */
	private static class ThrowingLogger extends CapturingLogger {
		ThrowingLogger() {
			super(new ArrayList<>());
		}

		@Override
		public void info(String msg) {
			throw new RuntimeException("Simulated logging failure");
		}
	}
}
