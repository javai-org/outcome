package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.javai.outcome.Outcome;
import org.javai.outcome.retry.Retrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates corrective retry with a failure interpreter.
 *
 * <p>This pattern is useful for LLM interactions where error context can be fed
 * back into the prompt to help the model self-correct.
 */
public class CorrectiveRetryTest {

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
	}

	/**
	 * Demonstrates corrective retry where the failure interpreter transforms
	 * technical errors into domain-specific guidance for the next attempt.
	 */
	@Test
	void correctiveRetry_transformsErrorsIntoGuidance() {
		List<String> feedbackReceived = new ArrayList<>();
		AtomicInteger attempts = new AtomicInteger(0);

		// Simulate an LLM that improves based on feedback
		String[] simulatedResponses = {
				"""
{ invalid json }""",                          // Attempt 1: malformed JSON syntax
				"""
{"id": "123", "items": ["A"]""",      // Attempt 2: unclosed brace
				"""
{"id": "123", "items": ["A"], "total": 99.99}"""  // Attempt 3: valid
		};

		Outcome<Order> result = Retrier.attemptWithFeedback(
				4,
				feedback -> {
					feedbackReceived.add(feedback);
					int attempt = attempts.getAndIncrement();
					String response = simulatedResponses[Math.min(attempt, simulatedResponses.length - 1)];
					return () -> objectMapper.readValue(response, Order.class);
				},
				failure -> {
					String message = failure.message();
					if (message.contains("Unexpected character")) {
						return "Your response contained invalid JSON syntax. Please return valid JSON.";
					}
					if (message.contains("end-of-input") || message.contains("Unexpected end")) {
						return "Your JSON was truncated. Please ensure all braces are closed.";
					}
					return "Previous attempt failed: " + message;
				}
		);

		assertThat(result.isOk()).isTrue();
		assertThat(result.getOrThrow()).isEqualTo(new Order("123", List.of("A"), 99.99));

		// Verify feedback was passed correctly
		assertThat(feedbackReceived).hasSize(3);
		assertThat(feedbackReceived.get(0)).isNull();  // First attempt has no feedback
		assertThat(feedbackReceived.get(1)).contains("invalid JSON syntax");
		assertThat(feedbackReceived.get(2)).contains("braces are closed");
	}

	/**
	 * Demonstrates that returning null from the interpreter means retry without feedback.
	 * Useful for transient errors where no guidance would help.
	 */
	@Test
	void correctiveRetry_nullFeedbackMeansRetryWithoutContext() {
		List<String> feedbackReceived = new ArrayList<>();
		AtomicInteger attempts = new AtomicInteger(0);

		Outcome<Order> result = Retrier.attemptWithFeedback(
				3,
				feedback -> {
					feedbackReceived.add(feedback);
					int attempt = attempts.incrementAndGet();
					if (attempt < 3) {
						return () -> {
							throw new Exception("Transient network error");
						};
					}
					return () -> new Order("456", List.of("B"), 50.00);
				},
				failure -> null  // No feedback for transient errors
		);

		assertThat(result.isOk()).isTrue();
		assertThat(feedbackReceived).hasSize(3);
		assertThat(feedbackReceived).containsOnly((String) null);  // All null
	}

	/**
	 * Demonstrates passing the raw failure to the retry attempt
	 * (without an interpreter) for direct access to failure details.
	 */
	@Test
	void correctiveRetry_directFailureAccess() {
		AtomicInteger attempts = new AtomicInteger(0);
		List<String> errorCodes = new ArrayList<>();

		Outcome<Order> result = Retrier.attemptWithFeedback(
				3,
				lastFailure -> {
					if (lastFailure != null) {
						errorCodes.add(lastFailure.id().toString());
					}
					int attempt = attempts.incrementAndGet();
					if (attempt < 3) {
						return () -> objectMapper.readValue("bad json", Order.class);
					}
					return () -> new Order("789", List.of("C"), 25.00);
				}
		);

		assertThat(result.isOk()).isTrue();
		assertThat(errorCodes).hasSize(2);  // Two failures before success
		assertThat(errorCodes).allMatch(code -> code.startsWith("retry:"));
	}

	record Order(String id, List<String> items, double total) {
	}
}
