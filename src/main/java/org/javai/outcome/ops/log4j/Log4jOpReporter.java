package org.javai.outcome.ops.log4j;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.javai.outcome.Cause;
import org.javai.outcome.Failure;
import org.javai.outcome.NotificationIntent;
import org.javai.outcome.ops.OpReporter;

import java.util.Map;

/**
 * Reports failures using Log4j2 structured logging.
 *
 * <p>Failures are logged with appropriate log levels based on their {@link NotificationIntent}:
 * <ul>
 *   <li>{@code PAGE} → ERROR</li>
 *   <li>{@code ALERT} → WARN</li>
 *   <li>{@code OBSERVE} → INFO</li>
 *   <li>{@code NONE} → DEBUG</li>
 * </ul>
 *
 * <p>Log entries include structured context via MDC-style key-value pairs for integration
 * with log aggregation systems (ELK, Splunk, etc.).
 */
public class Log4jOpReporter implements OpReporter {

	private static final Marker FAILURE_MARKER = MarkerManager.getMarker("FAILURE");
	private static final Marker RETRY_MARKER = MarkerManager.getMarker("RETRY");
	private static final Marker RETRY_EXHAUSTED_MARKER = MarkerManager.getMarker("RETRY_EXHAUSTED");

	private final Logger logger;

	/**
	 * Creates a Log4jOpReporter using the default logger name.
	 */
	public Log4jOpReporter() {
		this(LogManager.getLogger("org.javai.outcome.OpReporter"));
	}

	/**
	 * Creates a Log4jOpReporter with a custom logger name.
	 *
	 * @param loggerName the logger name
	 */
	public Log4jOpReporter(String loggerName) {
		this(LogManager.getLogger(loggerName));
	}

	/**
	 * Creates a Log4jOpReporter with a specific logger instance.
	 *
	 * @param logger the Log4j logger to use
	 */
	public Log4jOpReporter(Logger logger) {
		this.logger = logger;
	}

	@Override
	public void report(Failure failure) {
		Level level = levelFor(failure.notificationIntent());

		logger.atLevel(level)
			.withMarker(FAILURE_MARKER)
			.log(formatFailureMessage(failure));
	}

	@Override
	public void reportRetryAttempt(Failure failure, int attemptNumber, String policyId) {
		logger.atInfo()
			.withMarker(RETRY_MARKER)
			.log("Retry attempt {} for operation [{}] with policy [{}]. Code: {}, Message: {}",
				attemptNumber,
				failure.operation(),
				policyId,
				failure.code(),
				failure.message());
	}

	@Override
	public void reportRetryExhausted(Failure failure, int totalAttempts, String policyId) {
		logger.atWarn()
			.withMarker(RETRY_EXHAUSTED_MARKER)
			.log("Retry exhausted for operation [{}] after {} attempts with policy [{}]. Code: {}, Message: {}",
				failure.operation(),
				totalAttempts,
				policyId,
				failure.code(),
				failure.message());
	}

	private String formatFailureMessage(Failure failure) {
		return """
			Failure in operation [%s]: %s \
			| code=%s, category=%s, stability=%s, notification=%s%s%s%s\
			""".formatted(
				failure.operation(),
				failure.message(),
				failure.code(),
				failure.category(),
				failure.stability(),
				failure.notificationIntent(),
				formatCorrelationId(failure.correlationId()),
				formatTags(failure.tags()),
				formatCause(failure.cause())
			).trim();
	}

	private static String formatCorrelationId(String correlationId) {
		return correlationId != null ? ", correlationId=" + correlationId : "";
	}

	private static String formatTags(Map<String, String> tags) {
		if (tags == null || tags.isEmpty()) {
			return "";
		}
		return ", tags={" + tags.entrySet().stream()
				.map(e -> e.getKey() + "=" + e.getValue())
				.reduce((a, b) -> a + ", " + b)
				.orElse("") + "}";
	}

	private static String formatCause(Cause cause) {
		return cause != null ? ", cause=" + cause.type() : "";
	}

	private static Level levelFor(NotificationIntent intent) {
		return switch (intent) {
			case PAGE -> Level.ERROR;
			case ALERT -> Level.WARN;
			case OBSERVE -> Level.INFO;
			case NONE -> Level.DEBUG;
		};
	}
}
