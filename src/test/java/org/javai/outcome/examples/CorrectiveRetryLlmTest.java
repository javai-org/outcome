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
		String userMessage = "Add 3 apples to my basket";
		MockChatClient chatClient = new MockChatClient(
				// First response: invalid JSON (missing closing brace)
				"""
				{"command": "addToBasket", "item": "apples", "quantity": 3""",
				// Second response: valid JSON after receiving feedback
				"""
				{"command": "addToBasket", "item": "apples", "quantity": 3}""");

		Outcome<BasketCommand> result = Retrier.attemptWithFeedback(
				3,
				feedback -> {
					String fullUserMessage = feedback == null
							? userMessage
							: userMessage + "\n\nPrevious error: " + feedback;
					String response = chatClient.chat(SYSTEM_PROMPT, fullUserMessage);
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

		// Verify the chat client received the expected messages
		assertThat(chatClient.getReceivedMessages()).hasSize(2);

		MockChatClient.Message firstCall = chatClient.getReceivedMessages().get(0);
		assertThat(firstCall.systemMessage()).isEqualTo(SYSTEM_PROMPT);
		assertThat(firstCall.userMessage()).isEqualTo(userMessage);

		MockChatClient.Message secondCall = chatClient.getReceivedMessages().get(1);
		assertThat(secondCall.systemMessage()).isEqualTo(SYSTEM_PROMPT);
		assertThat(secondCall.userMessage()).contains(userMessage).contains("braces and brackets are closed");
	}

	@Test
	void correctiveRetry_llmReturnsValidJsonOnFirstAttempt() {
		String userMessage = "Add 5 oranges to my basket";
		MockChatClient chatClient = new MockChatClient(
				"""
				{"command": "addToBasket", "item": "oranges", "quantity": 5}""");

		Outcome<BasketCommand> result = Retrier.attemptWithFeedback(
				3,
				feedback -> {
					String response = chatClient.chat(SYSTEM_PROMPT, userMessage);
					return () -> objectMapper.readValue(response, BasketCommand.class);
				},
				failure -> "JSON parsing failed: " + failure.kind().message());

		assertThat(result.isOk()).isTrue();
		assertThat(result.getOrThrow().item()).isEqualTo("oranges");

		// Verify only one call was made (no retries needed)
		assertThat(chatClient.getReceivedMessages()).hasSize(1);
		assertThat(chatClient.getReceivedMessages().getFirst().userMessage()).isEqualTo(userMessage);
	}

	@Test
	void correctiveRetry_llmReceivesMultipleFeedbackRounds() {
		String userMessage = "Add 2 bananas to my basket";
		MockChatClient chatClient = new MockChatClient(
				// First: completely invalid - not JSON at all
				"Sure! I'll add bananas to your basket.",
				// Second: invalid JSON - unclosed string
				"""
				{"command": "addToBasket", "item": "bananas""",
				// Third: valid
				"""
				{"command": "addToBasket", "item": "bananas", "quantity": 2}""");

		Outcome<BasketCommand> result = Retrier.attemptWithFeedback(
				4,
				feedback -> {
					String fullUserMessage = feedback == null
							? userMessage
							: userMessage + "\n\nPrevious error: " + feedback;
					String response = chatClient.chat(SYSTEM_PROMPT, fullUserMessage);
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

		// Verify three calls were made with progressively more feedback
		List<MockChatClient.Message> messages = chatClient.getReceivedMessages();
		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).userMessage()).isEqualTo(userMessage);
		assertThat(messages.get(1).userMessage()).contains("only JSON");
		assertThat(messages.get(2).userMessage()).contains("incomplete");
	}

	/**
	 * Mock chat client that simulates an LLM API accepting system and user messages.
	 *
	 * <p>Returns pre-configured responses in sequence, capturing each request
	 * for verification in tests.
	 */
	private static class MockChatClient {
		private final String[] responses;
		private final List<Message> receivedMessages = new ArrayList<>();
		private int callCount = 0;

		MockChatClient(String... responses) {
			this.responses = responses;
		}

		String chat(String systemMessage, String userMessage) {
			receivedMessages.add(new Message(systemMessage, userMessage));
			int index = Math.min(callCount++, responses.length - 1);
			return responses[index];
		}

		List<Message> getReceivedMessages() {
			return receivedMessages;
		}

		record Message(String systemMessage, String userMessage) {}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BasketCommand(String command, String item, Integer quantity) {}
}
