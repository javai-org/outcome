package org.javai.outcome.ops;

import org.javai.outcome.Failure;

import java.time.format.DateTimeFormatter;

/**
 * Shared utilities for OpReporter implementations.
 */
public final class OpReporterUtils {

	private OpReporterUtils() {
		// Utility class
	}

	/**
	 * Resolves configuration from system property or environment variable.
	 *
	 * @param sysProp the system property name
	 * @param envVar the environment variable name
	 * @return the resolved value
	 * @throws IllegalStateException if neither is set
	 */
	public static String resolveConfig(String sysProp, String envVar) {
		String value = System.getProperty(sysProp);
		if (value == null || value.isBlank()) {
			value = System.getenv(envVar);
		}
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
				"Missing required configuration: set system property '" + sysProp +
				"' or environment variable '" + envVar + "'"
			);
		}
		return value;
	}

	/**
	 * Validates that a value is not null or blank.
	 *
	 * @param value the value to check
	 * @param name the name of the parameter (for error messages)
	 * @return the value if valid
	 * @throws IllegalArgumentException if value is null or blank
	 */
	public static String requireNonEmpty(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be null or empty");
		}
		return value;
	}

	/**
	 * Escapes special characters for JSON string values.
	 */
	public static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	/**
	 * Formats the correlation ID for display, returning "none" if not present.
	 */
	public static String formatCorrelationId(Failure failure) {
		return escapeJson(failure.correlationId() != null ? failure.correlationId() : "none");
	}

	/**
	 * Formats the failure timestamp as ISO offset date-time.
	 */
	public static String formatTimestamp(Failure failure) {
		return failure.occurredAt()
				.atZone(java.time.ZoneId.systemDefault())
				.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}
}
