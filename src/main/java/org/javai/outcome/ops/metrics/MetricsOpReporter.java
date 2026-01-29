package org.javai.outcome.ops.metrics;

import org.javai.outcome.Failure;
import org.javai.outcome.ops.OpReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Reports failures as JSON-lines metrics via SLF4J.
 *
 * <p>Outputs structured JSON for each failure event, suitable for metrics aggregation
 * and analysis pipelines. The JSON format includes all failure context with a
 * configurable namespace prefix for the tracking key.</p>
 *
 * <p>Example output:</p>
 * <pre>{@code
 * {"eventType":"failure","timestamp":"2024-01-20T10:30:00Z","trackingKey":"myapp.order.fetch","code":"http:timeout",...}
 * }</pre>
 *
 * <p>Constructor options follow the Log4jOpReporter pattern:</p>
 * <ul>
 *   <li>{@link #MetricsOpReporter()} - no namespace, default logger</li>
 *   <li>{@link #MetricsOpReporter(String)} - with namespace, default logger</li>
 *   <li>{@link #MetricsOpReporter(String, String)} - with namespace and custom logger name</li>
 * </ul>
 */
public class MetricsOpReporter implements OpReporter {

	private static final String DEFAULT_LOGGER_NAME = "org.javai.outcome.Metrics";
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

	private final String namespace;
	private final Logger logger;

	/**
	 * Creates a MetricsOpReporter with no namespace and the default logger.
	 */
	public MetricsOpReporter() {
		this(null, LoggerFactory.getLogger(DEFAULT_LOGGER_NAME));
	}

	/**
	 * Creates a MetricsOpReporter with the specified namespace and default logger.
	 *
	 * @param namespace the namespace to prepend to tracking keys (may be null or empty)
	 */
	public MetricsOpReporter(String namespace) {
		this(namespace, LoggerFactory.getLogger(DEFAULT_LOGGER_NAME));
	}

	/**
	 * Creates a MetricsOpReporter with the specified namespace and custom logger name.
	 *
	 * @param namespace the namespace to prepend to tracking keys (may be null or empty)
	 * @param loggerName the logger name
	 */
	public MetricsOpReporter(String namespace, String loggerName) {
		this(namespace, LoggerFactory.getLogger(loggerName));
	}

	/**
	 * Creates a MetricsOpReporter with explicit configuration.
	 * Package-private for testing.
	 *
	 * @param namespace the namespace to prepend to tracking keys (may be null or empty)
	 * @param logger the SLF4J logger to use
	 */
	MetricsOpReporter(String namespace, Logger logger) {
		this.namespace = normalizeNamespace(namespace);
		this.logger = logger;
	}

	@Override
	public void report(Failure failure) {
		try {
			String json = buildFailureJson(failure);
			logger.info(json);
		} catch (Exception e) {
			// Reporting should not break the application
		}
	}

	@Override
	public void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {
		try {
			String json = buildRetryAttemptJson(failure, attemptNumber, delay);
			logger.info(json);
		} catch (Exception e) {
			// Reporting should not break the application
		}
	}

	@Override
	public void reportRetryExhausted(Failure failure, int totalAttempts) {
		try {
			String json = buildRetryExhaustedJson(failure, totalAttempts);
			logger.info(json);
		} catch (Exception e) {
			// Reporting should not break the application
		}
	}

	private String buildFailureJson(Failure failure) {
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		appendField(sb, "eventType", "failure", true);
		appendField(sb, "timestamp", ISO_FORMATTER.format(failure.occurredAt()), false);
		appendField(sb, "trackingKey", buildTrackingKey(failure), false);
		appendField(sb, "code", failure.id().toString(), false);
		appendField(sb, "message", failure.message(), false);
		appendField(sb, "type", failure.type().name(), false);
		appendField(sb, "notification", failure.notificationIntent().name(), false);
		appendField(sb, "operation", failure.operation(), false);
		if (failure.correlationId() != null) {
			appendField(sb, "correlationId", failure.correlationId(), false);
		}
		appendTags(sb, failure.tags());
		sb.append("}");
		return sb.toString();
	}

	private String buildRetryAttemptJson(Failure failure, int attemptNumber, Duration delay) {
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		appendField(sb, "eventType", "retry_attempt", true);
		appendField(sb, "timestamp", ISO_FORMATTER.format(failure.occurredAt()), false);
		appendField(sb, "trackingKey", buildTrackingKey(failure), false);
		appendField(sb, "attemptNumber", String.valueOf(attemptNumber), false);
		appendField(sb, "delayMs", String.valueOf(delay.toMillis()), false);
		appendField(sb, "code", failure.id().toString(), false);
		appendField(sb, "operation", failure.operation(), false);
		if (failure.correlationId() != null) {
			appendField(sb, "correlationId", failure.correlationId(), false);
		}
		sb.append("}");
		return sb.toString();
	}

	private String buildRetryExhaustedJson(Failure failure, int totalAttempts) {
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		appendField(sb, "eventType", "retry_exhausted", true);
		appendField(sb, "timestamp", ISO_FORMATTER.format(failure.occurredAt()), false);
		appendField(sb, "trackingKey", buildTrackingKey(failure), false);
		appendField(sb, "totalAttempts", String.valueOf(totalAttempts), false);
		appendField(sb, "code", failure.id().toString(), false);
		appendField(sb, "operation", failure.operation(), false);
		if (failure.correlationId() != null) {
			appendField(sb, "correlationId", failure.correlationId(), false);
		}
		sb.append("}");
		return sb.toString();
	}

	String buildTrackingKey(Failure failure) {
		if (namespace == null || namespace.isEmpty()) {
			return failure.trackingId();
		}
		return namespace + "." + failure.trackingId();
	}

	private void appendField(StringBuilder sb, String key, String value, boolean first) {
		if (!first) {
			sb.append(",");
		}
		sb.append("\"").append(key).append("\":\"").append(escapeJson(value)).append("\"");
	}

	private void appendTags(StringBuilder sb, Map<String, String> tags) {
		if (tags == null || tags.isEmpty()) {
			return;
		}
		sb.append(",\"tags\":{");
		boolean first = true;
		for (Map.Entry<String, String> entry : tags.entrySet()) {
			if (!first) {
				sb.append(",");
			}
			sb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
			  .append(escapeJson(entry.getValue())).append("\"");
			first = false;
		}
		sb.append("}");
	}

	private static String normalizeNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			return null;
		}
		return namespace.trim();
	}

	static String escapeJson(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
