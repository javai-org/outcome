package org.javai.outcome.ops.teams;

import org.javai.outcome.Failure;
import org.javai.outcome.NotificationIntent;
import org.javai.outcome.ops.OpReporter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/**
 * Reports failures to Microsoft Teams via Incoming Webhooks.
 *
 * <p>Configuration is provided via system properties with environment variable fallbacks:
 * <ul>
 *   <li>{@code teams.webhook.url} / {@code TEAMS_WEBHOOK_URL} - Incoming webhook URL (required)</li>
 * </ul>
 *
 * <p>To create an Incoming Webhook in Teams:
 * <ol>
 *   <li>Go to the channel where you want notifications</li>
 *   <li>Click "..." → "Connectors"</li>
 *   <li>Find "Incoming Webhook" and click "Configure"</li>
 *   <li>Name it and copy the webhook URL</li>
 * </ol>
 */
public class TeamsOpReporter implements OpReporter {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final String webhookUrl;
	private final HttpClient httpClient;

	/**
	 * Creates a TeamsOpReporter using configuration from system properties or environment variables.
	 *
	 * @throws IllegalStateException if required configuration is missing
	 */
	public TeamsOpReporter() {
		this(resolveConfig("teams.webhook.url", "TEAMS_WEBHOOK_URL"));
	}

	/**
	 * Creates a TeamsOpReporter with an explicit webhook URL.
	 *
	 * @param webhookUrl the Teams incoming webhook URL
	 */
	public TeamsOpReporter(String webhookUrl) {
		this(webhookUrl, HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.build());
	}

	/**
	 * Creates a TeamsOpReporter with explicit configuration and a custom HttpClient.
	 * Useful for testing.
	 */
	TeamsOpReporter(String webhookUrl, HttpClient httpClient) {
		this.webhookUrl = requireNonEmpty(webhookUrl, "webhook URL");
		this.httpClient = httpClient;
	}

	@Override
	public void report(Failure failure) {
		String message = buildFailureMessage(failure);
		sendMessage(message);
	}

	private String buildFailureMessage(Failure failure) {
		String color = colorFor(failure.notificationIntent());
		String emoji = emojiFor(failure.notificationIntent());

		return """
			{
				"@type": "MessageCard",
				"@context": "http://schema.org/extensions",
				"themeColor": "%s",
				"summary": "Failure: %s",
				"sections": [{
					"activityTitle": "%s Failure: %s",
					"facts": [
						{"name": "Operation", "value": "%s"},
						{"name": "Code", "value": "%s"},
						{"name": "Type", "value": "%s"},
						{"name": "Message", "value": "%s"},
						{"name": "Correlation", "value": "%s"},
						{"name": "Occurred", "value": "%s"}
					],
					"markdown": true
				}]
			}
			""".formatted(
				color,
				escapeJson(failure.id().toString()),
				emoji,
				escapeJson(failure.id().toString()),
				escapeJson(failure.operation()),
				escapeJson(failure.id().toString()),
				failure.type(),
				escapeJson(failure.message()),
				formatCorrelationId(failure),
				formatTimestamp(failure)
			);
	}

	@Override
	public void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {
		String message = buildRetryAttemptMessage(failure, attemptNumber, delay);
		sendMessage(message);
	}

	private String buildRetryAttemptMessage(Failure failure, int attemptNumber, Duration delay) {
		return """
			{
				"@type": "MessageCard",
				"@context": "http://schema.org/extensions",
				"themeColor": "ffcc00",
				"summary": "Retry Attempt %d",
				"sections": [{
					"activityTitle": "🔄 Retry Attempt %d (waiting %dms)",
					"facts": [
						{"name": "Operation", "value": "%s"},
						{"name": "Code", "value": "%s"}
					],
					"markdown": true
				}]
			}
			""".formatted(
				attemptNumber,
				attemptNumber,
				delay.toMillis(),
				escapeJson(failure.operation()),
				escapeJson(failure.id().toString())
			);
	}

	@Override
	public void reportRetryExhausted(Failure failure, int totalAttempts) {
		String message = buildRetryExhaustedMessage(failure, totalAttempts);
		sendMessage(message);
	}

	private String buildRetryExhaustedMessage(Failure failure, int totalAttempts) {
		return """
			{
				"@type": "MessageCard",
				"@context": "http://schema.org/extensions",
				"themeColor": "ff0000",
				"summary": "Retry Exhausted",
				"sections": [{
					"activityTitle": "❌ Retry Exhausted",
					"facts": [
						{"name": "Operation", "value": "%s"},
						{"name": "Total Attempts", "value": "%d"},
						{"name": "Final Error", "value": "%s"}
					],
					"markdown": true
				}]
			}
			""".formatted(
				escapeJson(failure.operation()),
				totalAttempts,
				escapeJson(failure.id().toString())
			);
	}

	private void sendMessage(String jsonBody) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(webhookUrl))
				.header("Content-Type", "application/json; charset=utf-8")
				.timeout(TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();

		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() != 200) {
						System.err.println("Teams webhook returned status " + response.statusCode() + ": " + response.body());
					}
				})
				.exceptionally(e -> {
					System.err.println("Failed to send Teams notification: " + e.getMessage());
					return null;
				});
	}

	private static String resolveConfig(String sysProp, String envVar) {
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

	private static String requireNonEmpty(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be null or empty");
		}
		return value;
	}

	private static String emojiFor(NotificationIntent intent) {
		return switch (intent) {
			case NONE -> "ℹ️";
			case OBSERVE -> "👀";
			case ALERT -> "⚠️";
			case PAGE -> "🚨";
		};
	}

	private static String colorFor(NotificationIntent intent) {
		// Teams uses hex colors without the # prefix
		return switch (intent) {
			case NONE -> "36a64f";      // green
			case OBSERVE -> "439fe0";   // blue
			case ALERT -> "ffcc00";     // yellow
			case PAGE -> "ff0000";      // red
		};
	}

	private static String formatCorrelationId(Failure failure) {
		return escapeJson(failure.correlationId() != null ? failure.correlationId() : "none");
	}

	private static String formatTimestamp(Failure failure) {
		return failure.occurredAt()
				.atZone(java.time.ZoneId.systemDefault())
				.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	private static String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
