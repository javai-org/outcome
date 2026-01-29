package org.javai.outcome.ops.log4j;

import java.time.Duration;
import java.util.Map;
import org.javai.outcome.Failure;
import org.javai.outcome.NotificationIntent;
import org.javai.outcome.ops.OpReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Reports failures using SLF4J structured logging.
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
 *
 * <p>Note: This class uses the SLF4J facade, which can be bound to Log4j2, Logback,
 * or other logging implementations at runtime.
 */
public class Log4jOpReporter implements OpReporter {

	private static final Marker FAILURE_MARKER = MarkerFactory.getMarker("FAILURE");
	private static final Marker RETRY_MARKER = MarkerFactory.getMarker("RETRY");
	private static final Marker RETRY_EXHAUSTED_MARKER = MarkerFactory.getMarker("RETRY_EXHAUSTED");

	private final Logger logger;

	/**
	 * Creates a Log4jOpReporter using the default logger name.
	 */
	public Log4jOpReporter() {
		this(LoggerFactory.getLogger("org.javai.outcome.OpReporter"));
	}

	/**
	 * Creates a Log4jOpReporter with a custom logger name.
	 *
	 * @param loggerName the logger name
	 */
	public Log4jOpReporter(String loggerName) {
		this(LoggerFactory.getLogger(loggerName));
	}

	/**
	 * Creates a Log4jOpReporter with a specific logger instance.
	 *
	 * @param logger the SLF4J logger to use
	 */
	public Log4jOpReporter(Logger logger) {
		this.logger = logger;
	}

	@Override
	public void report(Failure failure) {
		String message = formatFailureMessage(failure);
		logAtLevel(failure.notificationIntent(), FAILURE_MARKER, message);
	}

	@Override
	public void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {
		logger.info(RETRY_MARKER,
				"Retry attempt {} for operation [{}], waiting {}ms. Code: {}, Message: {}",
				attemptNumber,
				failure.operation(),
				delay.toMillis(),
				failure.id(),
				failure.message());
	}

	@Override
	public void reportRetryExhausted(Failure failure, int totalAttempts) {
		logger.warn(RETRY_EXHAUSTED_MARKER,
				"Retry exhausted for operation [{}] after {} attempts. Code: {}, Message: {}",
				failure.operation(),
				totalAttempts,
				failure.id(),
				failure.message());
	}

	private void logAtLevel(NotificationIntent intent, Marker marker, String message) {
		switch (intent) {
			case PAGE -> logger.error(marker, message);
			case ALERT -> logger.warn(marker, message);
			case OBSERVE -> logger.info(marker, message);
			case NONE -> logger.debug(marker, message);
		}
	}

	private String formatFailureMessage(Failure failure) {
		return """
			Failure in operation [%s]: %s \
			| id=%s, type=%s, notification=%s%s%s%s\
			""".formatted(
				failure.operation(),
				failure.message(),
				failure.id(),
				failure.type(),
				failure.notificationIntent(),
				formatCorrelationId(failure.correlationId()),
				formatTags(failure.tags()),
				formatException(failure.exception())
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

	private static String formatException(Throwable exception) {
		return exception != null ? ", exception=" + exception.getClass().getName() : "";
	}
}
