package org.javai.outcome;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Outcome correlation ID functionality.
 */
class OutcomeCorrelationTest {

    // === Ok correlation ID tests ===

    @Test
    void ok_withoutCorrelationId_hasEmptyCorrelationId() {
        Outcome<String> outcome = Outcome.ok("hello");

        assertThat(outcome.correlationId()).isEmpty();
    }

    @Test
    void ok_correlationIdMethod_setsCorrelationId() {
        Outcome<String> outcome = Outcome.ok("hello").correlationId("corr-123");

        assertThat(outcome.correlationId()).contains("corr-123");
        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.getOrThrow()).isEqualTo("hello");
    }

    @Test
    void ok_correlationIdWithNull_setsEmptyOptional() {
        Outcome<String> outcome = Outcome.ok("hello")
                .correlationId("corr-123")
                .correlationId(null);

        assertThat(outcome.correlationId()).isEmpty();
    }

    @Test
    void ok_recordAccessor_returnsCorrelationId() {
        Outcome.Ok<String> ok = new Outcome.Ok<>("hello", Optional.of("corr-456"));

        assertThat(ok.correlationId()).contains("corr-456");
    }

    // === Fail correlation ID tests ===

    @Test
    void fail_withoutCorrelationId_hasEmptyCorrelationId() {
        Outcome<String> outcome = Outcome.fail(createTestFailure("error"));

        assertThat(outcome.correlationId()).isEmpty();
    }

    @Test
    void fail_correlationIdMethod_setsCorrelationId() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("corr-789");

        assertThat(outcome.correlationId()).contains("corr-789");
        assertThat(outcome.isFail()).isTrue();
    }

    @Test
    void fail_correlationIdWithNull_setsEmptyOptional() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("corr-789")
                .correlationId(null);

        assertThat(outcome.correlationId()).isEmpty();
    }

    @Test
    void fail_recordAccessor_returnsCorrelationId() {
        Outcome.Fail<String> fail = new Outcome.Fail<>(createTestFailure("error"), Optional.of("corr-abc"));

        assertThat(fail.correlationId()).contains("corr-abc");
    }

    // === map preserves correlation ID ===

    @Test
    void ok_map_preservesCorrelationId() {
        Outcome<String> outcome = Outcome.ok("hello").correlationId("corr-map");

        Outcome<Integer> mapped = outcome.map(String::length);

        assertThat(mapped.correlationId()).contains("corr-map");
        assertThat(mapped.getOrThrow()).isEqualTo(5);
    }

    @Test
    void fail_map_preservesCorrelationId() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("corr-fail-map");

        Outcome<Integer> mapped = outcome.map(String::length);

        assertThat(mapped.correlationId()).contains("corr-fail-map");
        assertThat(mapped.isFail()).isTrue();
    }

    // === flatMap preserves correlation ID ===

    @Test
    void ok_flatMap_preservesCorrelationIdWhenResultHasNone() {
        Outcome<String> outcome = Outcome.ok("hello").correlationId("corr-flat");

        Outcome<Integer> flatMapped = outcome.flatMap(s -> Outcome.ok(s.length()));

        assertThat(flatMapped.correlationId()).contains("corr-flat");
    }

    @Test
    void ok_flatMap_resultCorrelationIdTakesPrecedence() {
        Outcome<String> outcome = Outcome.ok("hello").correlationId("outer-corr");

        Outcome<Integer> flatMapped = outcome.flatMap(s ->
                Outcome.ok(s.length()).correlationId("inner-corr"));

        assertThat(flatMapped.correlationId()).contains("inner-corr");
    }

    @Test
    void fail_flatMap_preservesCorrelationId() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("corr-fail-flat");

        Outcome<Integer> flatMapped = outcome.flatMap(s -> Outcome.ok(s.length()));

        assertThat(flatMapped.correlationId()).contains("corr-fail-flat");
    }

    // === recover preserves correlation ID ===

    @Test
    void ok_recover_preservesCorrelationId() {
        Outcome<String> outcome = Outcome.ok("hello").correlationId("corr-recover");

        Outcome<String> recovered = outcome.recover(f -> "recovered");

        assertThat(recovered.correlationId()).contains("corr-recover");
        assertThat(recovered.getOrThrow()).isEqualTo("hello");
    }

    @Test
    void fail_recover_preservesCorrelationId() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("corr-fail-recover");

        Outcome<String> recovered = outcome.recover(f -> "recovered");

        assertThat(recovered.correlationId()).contains("corr-fail-recover");
        assertThat(recovered.isOk()).isTrue();
    }

    // === recoverWith preserves correlation ID ===

    @Test
    void ok_recoverWith_preservesCorrelationId() {
        Outcome<String> outcome = Outcome.ok("hello").correlationId("corr-recoverWith");

        Outcome<String> recovered = outcome.recoverWith(f -> Outcome.ok("recovered"));

        assertThat(recovered.correlationId()).contains("corr-recoverWith");
        assertThat(recovered.getOrThrow()).isEqualTo("hello");
    }

    @Test
    void fail_recoverWith_preservesCorrelationIdWhenResultHasNone() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("corr-fail-recoverWith");

        Outcome<String> recovered = outcome.recoverWith(f -> Outcome.ok("recovered"));

        assertThat(recovered.correlationId()).contains("corr-fail-recoverWith");
    }

    @Test
    void fail_recoverWith_resultCorrelationIdTakesPrecedence() {
        Outcome<String> outcome = Outcome.<String>fail(createTestFailure("error"))
                .correlationId("outer-corr");

        Outcome<String> recovered = outcome.recoverWith(f ->
                Outcome.ok("recovered").correlationId("inner-corr"));

        assertThat(recovered.correlationId()).contains("inner-corr");
    }

    // === Pattern matching with correlation ID ===

    @Test
    void patternMatching_withCorrelationId_works() {
        Outcome<String> ok = Outcome.ok("hello").correlationId("corr-pattern");
        Outcome<String> fail = Outcome.<String>fail(createTestFailure("error")).correlationId("corr-fail-pattern");

        String okResult = switch (ok) {
            case Outcome.Ok<String> o -> "got: " + o.value() + ", corr: " + o.correlationId().orElse("none");
            case Outcome.Fail<String> f -> "failed: " + f.failure().message();
        };

        String failResult = switch (fail) {
            case Outcome.Ok<String> o -> "got: " + o.value();
            case Outcome.Fail<String> f -> "failed: " + f.failure().message() + ", corr: " + f.correlationId().orElse("none");
        };

        assertThat(okResult).isEqualTo("got: hello, corr: corr-pattern");
        assertThat(failResult).isEqualTo("failed: error, corr: corr-fail-pattern");
    }

    // === Direct record construction ===

    @Test
    void ok_directConstruction_withEmptyOptional() {
        Outcome.Ok<String> ok = new Outcome.Ok<>("value", Optional.empty());

        assertThat(ok.correlationId()).isEmpty();
        assertThat(ok.value()).isEqualTo("value");
    }

    @Test
    void ok_directConstruction_rejectsNullOptional() {
        assertThatThrownBy(() -> new Outcome.Ok<>("value", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("correlationId must not be null");
    }

    @Test
    void fail_directConstruction_withEmptyOptional() {
        Outcome.Fail<String> fail = new Outcome.Fail<>(createTestFailure("error"), Optional.empty());

        assertThat(fail.correlationId()).isEmpty();
    }

    @Test
    void fail_directConstruction_rejectsNullOptional() {
        assertThatThrownBy(() -> new Outcome.Fail<>(createTestFailure("error"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("correlationId must not be null");
    }

    private Failure createTestFailure(String message) {
        return Failure.transientFailure(
                FailureId.of("test", "error"),
                message,
                "TestOperation",
                null
        );
    }
}
