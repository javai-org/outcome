package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.javai.outcome.Outcome;
import org.javai.outcome.retry.Retrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates corrective retry with a mock LLM for shopping basket commands.
 *
 * <p>This example simulates an LLM that receives user instructions (e.g., "add 2 bananas to my
 * basket") and should return a structured JSON command. When the LLM returns invalid output, the
 * corrective retry mechanism provides feedback to help it self-correct.
 */
public class CorrectiveRetryLlmTest {

	private static final String SYSTEM_PROMPT =
			"""
			You are a shopping assistant. Parse user requests and return JSON commands.
			For adding items, return: {"command": "addToBasket", "item": "<item>", "quantity": <n>}
			Always return valid JSON. Do not include any text outside the JSON object.""";

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
	}

	@Test
	void correctiveRetry_llmSelfCorrectsAfterInvalidJsonFeedback() {
		String userInput = "Add 3 apples to my basket";
		MockLlm mockLlm = new MockLlm(
				// First response: invalid JSON (missing closing brace)
				"""
				{"command": "addToBasket", "item": "apples", "quantity": 3""",
				// Second response: valid JSON after receiving feedback
				"""
				{"command": "addToBasket", "item": "apples", "quantity": 3}""");

		List<String> promptsReceived = new ArrayList<>();

		Outcome<BasketCommand> result = Retrier.attemptWithFeedback(
				3,
				feedback -> {
					String prompt = buildPrompt(userInput, feedback);
					promptsReceived.add(prompt);
					String response = mockLlm.respond();
					return () -> objectMapper.readValue(response, BasketCommand.class);
				},
				failure -> {
					String message = failure.kind().message();
					if (message.contains("end-of-input") || message.contains("Unexpected end")) {
						return "Your JSON was incomplete. Please ensure all braces and brackets are closed.";
					}
					if (message.contains("Unexpected character")) {
						return "Invalid JSON syntax. Please return only valid JSON with no extra text.";
					}
					return "JSON parsing failed: " + message;
				});

		assertThat(result.isOk()).isTrue();
		BasketCommand command = result.getOrThrow();
		assertThat(command.command()).isEqualTo("addToBasket");
		assertThat(command.item()).isEqualTo("apples");
		assertThat(command.quantity()).isEqualTo(3);

		// Verify prompt progression
		assertThat(promptsReceived).hasSize(2);
		assertThat(promptsReceived.get(0)).contains(SYSTEM_PROMPT).contains(userInput).doesNotContain("Previous error");
		assertThat(promptsReceived.get(1))
				.contains(SYSTEM_PROMPT)
				.contains(userInput)
				.contains("Previous error")
				.contains("braces and brackets are closed");
	}

	@Test
	void correctiveRetry_llmReturnsValidJsonOnFirstAttempt() {
		String userInput = "Add 5 oranges to my basket";
		MockLlm mockLlm =
				new MockLlm("""
				{"command": "addToBasket", "item": "oranges", "quantity": 5}""");

		List<String> feedbackReceived = new ArrayList<>();

		Outcome<BasketCommand> result = Retrier.attemptWithFeedback(
				3,
				feedback -> {
					feedbackReceived.add(feedback);
					String response = mockLlm.respond();
					return () -> objectMapper.readValue(response, BasketCommand.class);
				},
				failure -> "JSON parsing failed: " + failure.kind().message());

		assertThat(result.isOk()).isTrue();
		assertThat(result.getOrThrow().item()).isEqualTo("oranges");
		assertThat(feedbackReceived).hasSize(1);
		assertThat(feedbackReceived.get(0)).isNull(); // No feedback on first attempt
	}

	@Test
	void correctiveRetry_llmReceivesMultipleFeedbackRounds() {
		String userInput = "Add 2 bananas to my basket";
		MockLlm mockLlm = new MockLlm(
				// First: completely invalid - not JSON at all
				"Sure! I'll add bananas to your basket.",
				// Second: invalid JSON - unclosed string
				"""
				{"command": "addToBasket", "item": "bananas""",
				// Third: valid
				"""
				{"command": "addToBasket", "item": "bananas", "quantity": 2}""");

		List<String> feedbackReceived = new ArrayList<>();

		Outcome<BasketCommand> result = Retrier.attemptWithFeedback(
				4,
				feedback -> {
					feedbackReceived.add(feedback);
					String response = mockLlm.respond();
					return () -> objectMapper.readValue(response, BasketCommand.class);
				},
				failure -> {
					String message = failure.kind().message();
					if (message.contains("Unrecognized token") || message.contains("Unexpected character")) {
						return "Please return only JSON. Do not include conversational text.";
					}
					if (message.contains("end-of-input") || message.contains("Unexpected end")) {
						return "Your JSON was incomplete. Please ensure all quotes and braces are closed.";
					}
					return "Error: " + message;
				});

		assertThat(result.isOk()).isTrue();
		assertThat(result.getOrThrow().item()).isEqualTo("bananas");

		assertThat(feedbackReceived).hasSize(3);
		assertThat(feedbackReceived.get(0)).isNull();
		assertThat(feedbackReceived.get(1)).contains("only JSON");
		assertThat(feedbackReceived.get(2)).contains("incomplete");
	}

	private String buildPrompt(String userInput, String feedback) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
		prompt.append("User: ").append(userInput);
		if (feedback != null) {
			prompt.append("\n\nPrevious error: ").append(feedback);
		}
		return prompt.toString();
	}

	/**
	 * Mock LLM that returns pre-configured responses in sequence.
	 */
	private static class MockLlm {
		private final String[] responses;
		private int callCount = 0;

		MockLlm(String... responses) {
			this.responses = responses;
		}

		String respond() {
			int index = Math.min(callCount++, responses.length - 1);
			return responses[index];
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BasketCommand(String command, String item, Integer quantity) {}
}
