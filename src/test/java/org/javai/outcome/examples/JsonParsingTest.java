package org.javai.outcome.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javai.outcome.Failure;
import org.javai.outcome.Outcome;
import org.javai.outcome.boundary.Boundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates parsing JSON with ObjectMapper through the Outcome framework.
 */
public class JsonParsingTest {

    record User(String name, int age) {}

    private Boundary boundary;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        boundary = Boundary.silent();
        objectMapper = new ObjectMapper();
    }

    @Test
    void validJson_parsesSuccessfully() {
        String json = """
            {"name": "Alice", "age": 30}
            """;

        Outcome<User> outcome = boundary.call("Json.parse",
                () -> objectMapper.readValue(json, User.class));

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.getOrThrow()).isEqualTo(new User("Alice", 30));
    }

    @Test
    void invalidJson_returnsFail() {
        String invalidJson = "not valid json";

        Outcome<User> outcome = boundary.call("Json.parse",
                () -> objectMapper.readValue(invalidJson, User.class));

        assertThat(outcome.isFail()).isTrue();
        Failure failure = ((Outcome.Fail<User>) outcome).failure();
        assertThat(failure.id().toString()).startsWith("io:");
    }
}
