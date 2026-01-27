package org.javai.outcome.ops.slack;

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
 * Reports failures to Slack via the Slack Web API.
 *
 * <p>Configuration is provided via system properties with environment variable fallbacks:
 * <ul>
 *   <li>{@code slack.api.token} / {@code SLACK_API_TOKEN} - Bot token (required)</li>
 *   <li>{@code slack.channel} / {@code SLACK_CHANNEL} - Channel ID or name (required)</li>
 * </ul>
 *
 * <p>The bot token must have the {@code chat:write} scope.
 */
public class SlackOpReporter implements OpReporter {

	private static final String SLACK_API_URL = "https://slack.com/api/chat.postMessage";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final String apiToken;
	private final String channel;
	private final HttpClient httpClient;

	/**
	 * Creates a SlackOpReporter using configuration from system properties or environment variables.
	 *
	 * @throws IllegalStateException if required configuration is missing
	 */
	public SlackOpReporter() {
		this(resolveConfig("slack.api.token", "SLACK_API_TOKEN"),
			 resolveConfig("slack.channel", "SLACK_CHANNEL"));
	}

	/**
	 * Creates a SlackOpReporter with explicit configuration.
	 *
	 * @param apiToken the Slack bot token
	 * @param channel the channel ID or name to post to
	 */
	public SlackOpReporter(String apiToken, String channel) {
		this(apiToken, channel, HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.build());
	}

	/**
	 * Creates a SlackOpReporter with explicit configuration and a custom HttpClient.
	 * Useful for testing.
	 */
	SlackOpReporter(String apiToken, String channel, HttpClient httpClient) {
		this.apiToken = requireNonEmpty(apiToken, "API token");
		this.channel = requireNonEmpty(channel, "channel");
		this.httpClient = httpClient;
	}

	@Override
	public void report(Failure failure) {
		String message = buildFailureMessage(failure);
		sendMessage(message);
	}

	private String buildFailureMessage(Failure failure) {
		String emoji = emojiFor(failure.notificationIntent());
		String color = colorFor(failure.notificationIntent());

		return """
			{
				"channel": "%s",
				"attachments": [{
					"color": "%s",
					"blocks": [
						{
							"type": "header",
							"text": {
								"type": "plain_text",
								"text": "%s Failure: %s",
								"emoji": true
							}
						},
						{
							"type": "section",
							"fields": [
								{"type": "mrkdwn", "text": "*Code:*\\n%s"},
								{"type": "mrkdwn", "text": "*Operation:*\\n%s"},
								{"type": "mrkdwn", "text": "*Type:*\\n%s"}
							]
						},
						{
							"type": "section",
							"text": {
								"type": "mrkdwn",
								"text": "*Message:*\\n%s"
							}
						},
						{
							"type": "context",
							"elements": [
								{"type": "mrkdwn", "text": "Correlation: %s | %s"}
							]
						}
					]
				}]
			}
			""".formatted(
				escapeJson(channel),
				color,
				emoji,
				escapeJson(failure.id().toString()),
				escapeJson(failure.id().toString()),
				escapeJson(failure.operation()),
				failure.type(),
				escapeJson(failure.message()),
				formatCorrelationId(failure),
				formatTimestamp(failure)
			);
	}

	@Override
	public void reportRetryAttempt(Failure failure, int attemptNumber, String policyId) {
		String message = buildRetryAttemptMessage(failure, attemptNumber, policyId);
		sendMessage(message);
	}

	private String buildRetryAttemptMessage(Failure failure, int attemptNumber, String policyId) {
		return """
			{
				"channel": "%s",
				"attachments": [{
					"color": "#ffcc00",
					"blocks": [
						{
							"type": "section",
							"text": {
								"type": "mrkdwn",
								"text": ":repeat: *Retry Attempt %d* for `%s`\\nPolicy: `%s` | Code: `%s`"
							}
						}
					]
				}]
			}
			""".formatted(
				escapeJson(channel),
				attemptNumber,
				escapeJson(failure.operation()),
				escapeJson(policyId),
				escapeJson(failure.id().toString())
			);
	}

	@Override
	public void reportRetryExhausted(Failure failure, int totalAttempts, String policyId) {
		String message = buildRetryExhaustedMessage(failure, totalAttempts, policyId);
		sendMessage(message);
	}

	private String buildRetryExhaustedMessage(Failure failure, int totalAttempts, String policyId) {
		return """
			{
				"channel": "%s",
				"attachments": [{
					"color": "#ff0000",
					"blocks": [
						{
							"type": "header",
							"text": {
								"type": "plain_text",
								"text": ":x: Retry Exhausted",
								"emoji": true
							}
						},
						{
							"type": "section",
							"fields": [
								{"type": "mrkdwn", "text": "*Operation:*\\n%s"},
								{"type": "mrkdwn", "text": "*Total Attempts:*\\n%d"},
								{"type": "mrkdwn", "text": "*Policy:*\\n%s"},
								{"type": "mrkdwn", "text": "*Final Error:*\\n%s"}
							]
						}
					]
				}]
			}
			""".formatted(
				escapeJson(channel),
				escapeJson(failure.operation()),
				totalAttempts,
				escapeJson(policyId),
				escapeJson(failure.id().toString())
			);
	}

	private void sendMessage(String jsonBody) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(SLACK_API_URL))
					.header("Authorization", "Bearer " + apiToken)
					.header("Content-Type", "application/json; charset=utf-8")
					.timeout(TIMEOUT)
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				System.err.println("Slack API returned status " + response.statusCode() + ": " + response.body());
			}
		} catch (Exception e) {
			// Log but don't throw - reporting should not break the application
			System.err.println("Failed to send Slack notification: " + e.getMessage());
		}
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
			case NONE -> ":information_source:";
			case OBSERVE -> ":eyes:";
			case ALERT -> ":warning:";
			case PAGE -> ":rotating_light:";
		};
	}

	private static String colorFor(NotificationIntent intent) {
		return switch (intent) {
			case NONE -> "#36a64f";      // green
			case OBSERVE -> "#439fe0";   // blue
			case ALERT -> "#ffcc00";     // yellow
			case PAGE -> "#ff0000";      // red
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
