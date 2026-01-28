package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javai.outcome.Failure;
import org.javai.outcome.Outcome;
import org.javai.outcome.retry.Retrier;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates guided retry with a mock LLM.
 *
 * <p>When an LLM returns invalid output (e.g., malformed JSON), the guided retry
 * mechanism derives guidance from the failure and feeds it back to the LLM,
 * allowing it to self-correct.
 */
public class CorrectiveRetryLlmTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void llmSelfCorrectsThroughGuidance() {
		String userRequest = "Add 2 bananas to my basket";

		// Simulate an LLM that initially struggles but improves with guidance
		MockChatClient llm = new MockChatClient(
				"Sure! I'll add bananas to your basket.",  // Wrong: conversational text
				"""
				{"command": "addToBasket", "item": "bananas""",  // Wrong: unclosed JSON
				"""
				{"command": "addToBasket", "item": "bananas", "quantity": 2}"""  // Correct
		);

		Outcome<BasketCommand> result = Retrier.withGuidance(4)
				.attempt(() -> objectMapper.readValue(llm.chat(userRequest), BasketCommand.class))
				.deriveGuidance(this::failureToGuidance)
				.reattempt(guidance -> () -> objectMapper.readValue(llm.chat(userRequest + guidance), BasketCommand.class))
				.execute();

		assertThat(result.isOk()).isTrue();
		assertThat(result.getOrThrow()).isEqualTo(new BasketCommand("addToBasket", "bananas", 2));
		assertThat(llm.getCallCount()).isEqualTo(3);
	}

	private String failureToGuidance(Failure failure) {
		String message = failure.message();
		if (message.contains("Unrecognized token") || message.contains("Unexpected character")) {
			return "\n\nError: Return only valid JSON, no conversational text.";
		}
		if (message.contains("end-of-input") || message.contains("Unexpected end")) {
			return "\n\nError: Your JSON was incomplete. Ensure all quotes and braces are closed.";
		}
		return "\n\nError: " + message;
	}

	/** Mock LLM that returns pre-configured responses in sequence. */
	private static class MockChatClient {
		private final String[] responses;
		private int callCount = 0;

		MockChatClient(String... responses) {
			this.responses = responses;
		}

		String chat(String prompt) {
			return responses[Math.min(callCount++, responses.length - 1)];
		}

		int getCallCount() {
			return callCount;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BasketCommand(String command, String item, Integer quantity) {}
}
