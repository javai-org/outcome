package org.javai.outcome;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OutcomeTest {

    @Test
    void ok_minimalOk() {
        Outcome<Void> outcome = Outcome.ok();

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.isFail()).isFalse();
        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        assertThat(((Outcome.Ok<Void>) outcome).value()).isNull();
    }


    @Test
    void ok_containsValue() {
        Outcome<String> outcome = Outcome.ok("hello");

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.isFail()).isFalse();
        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        assertThat(((Outcome.Ok<String>) outcome).value()).isEqualTo("hello");
    }

    @Test
    void ok_getOrThrow_returnsValue() {
        Outcome<String> outcome = Outcome.ok("hello");

        assertThat(outcome.getOrThrow()).isEqualTo("hello");
    }

    @Test
    void ok_getOrElse_returnsValue() {
        Outcome<String> outcome = Outcome.ok("hello");

        assertThat(outcome.getOrElse("default")).isEqualTo("hello");
    }

    @Test
    void fail_containsFailure() {
        Failure failure = createTestFailure("test failure");
        Outcome<String> outcome = Outcome.fail(failure);

        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.isFail()).isTrue();
        assertThat(outcome).isInstanceOf(Outcome.Fail.class);
        assertThat(((Outcome.Fail<String>) outcome).failure()).isEqualTo(failure);
    }

    @Test
    void fail_getOrThrow_throwsException() {
        Failure failure = createTestFailure("test failure");
        Outcome<String> outcome = Outcome.fail(failure);

        assertThatThrownBy(outcome::getOrThrow)
                .isInstanceOf(OutcomeFailedException.class)
                .hasMessageContaining("test failure")
                .extracting(e -> ((OutcomeFailedException) e).failure())
                .isEqualTo(failure);
    }

    @Test
    void fail_getOrElse_returnsDefault() {
        Outcome<String> outcome = Outcome.fail(createTestFailure("error"));

        assertThat(outcome.getOrElse("default")).isEqualTo("default");
    }

    @Test
    void fail_getOrElseGet_callsSupplier() {
        Outcome<String> outcome = Outcome.fail(createTestFailure("error"));

        assertThat(outcome.getOrElseGet(() -> "computed")).isEqualTo("computed");
    }

    @Test
    void ok_map_transformsValue() {
        Outcome<String> outcome = Outcome.ok("hello");

        Outcome<Integer> mapped = outcome.map(String::length);

        assertThat(mapped.isOk()).isTrue();
        assertThat(mapped.getOrThrow()).isEqualTo(5);
    }

    @Test
    void fail_map_propagatesFailure() {
        Failure failure = createTestFailure("error");
        Outcome<String> outcome = Outcome.fail(failure);

        Outcome<Integer> mapped = outcome.map(String::length);

        assertThat(mapped.isFail()).isTrue();
        assertThat(((Outcome.Fail<Integer>) mapped).failure()).isEqualTo(failure);
    }

    @Test
    void ok_flatMap_appliesFunction() {
        Outcome<String> outcome = Outcome.ok("hello");

        Outcome<Integer> flatMapped = outcome.flatMap(s -> Outcome.ok(s.length()));

        assertThat(flatMapped.isOk()).isTrue();
        assertThat(flatMapped.getOrThrow()).isEqualTo(5);
    }

    @Test
    void ok_flatMap_canReturnFailure() {
        Outcome<String> outcome = Outcome.ok("hello");
        Failure failure = createTestFailure("flatMap failure");

        Outcome<Integer> flatMapped = outcome.flatMap(s -> Outcome.fail(failure));

        assertThat(flatMapped.isFail()).isTrue();
    }

    @Test
    void fail_flatMap_propagatesFailure() {
        Failure failure = createTestFailure("original");
        Outcome<String> outcome = Outcome.fail(failure);

        Outcome<Integer> flatMapped = outcome.flatMap(s -> Outcome.ok(s.length()));

        assertThat(flatMapped.isFail()).isTrue();
        assertThat(((Outcome.Fail<Integer>) flatMapped).failure()).isEqualTo(failure);
    }

    @Test
    void ok_recover_returnsOriginal() {
        Outcome<String> outcome = Outcome.ok("hello");

        Outcome<String> recovered = outcome.recover(f -> "recovered");

        assertThat(recovered.getOrThrow()).isEqualTo("hello");
    }

    @Test
    void fail_recover_appliesRecovery() {
        Outcome<String> outcome = Outcome.fail(createTestFailure("error"));

        Outcome<String> recovered = outcome.recover(f -> "recovered");

        assertThat(recovered.isOk()).isTrue();
        assertThat(recovered.getOrThrow()).isEqualTo("recovered");
    }

    @Test
    void fail_recoverWith_appliesRecovery() {
        Outcome<String> outcome = Outcome.fail(createTestFailure("error"));

        Outcome<String> recovered = outcome.recoverWith(f -> Outcome.ok("recovered"));

        assertThat(recovered.isOk()).isTrue();
        assertThat(recovered.getOrThrow()).isEqualTo("recovered");
    }

    @Test
    void fail_recoverWith_canReturnFailure() {
        Outcome<String> outcome = Outcome.fail(createTestFailure("original"));
        Failure newFailure = createTestFailure("new failure");

        Outcome<String> recovered = outcome.recoverWith(f -> Outcome.fail(newFailure));

        assertThat(recovered.isFail()).isTrue();
        assertThat(((Outcome.Fail<String>) recovered).failure()).isEqualTo(newFailure);
    }

    @Test
    void fail_withNameAndMessage_createsDefectWithDefaultNamespace() {
        Outcome<String> outcome = Outcome.fail("missing_data", "Data point missing");

        assertThat(outcome.isFail()).isTrue();
        Failure failure = ((Outcome.Fail<String>) outcome).failure();
        assertThat(failure.id().namespace()).isEqualTo("org.javai.outcome");
        assertThat(failure.id().name()).isEqualTo("missing_data");
        assertThat(failure.message()).isEqualTo("Data point missing");
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void fail_withNamespaceNameAndMessage_createsDefectWithCustomNamespace() {
        Outcome<String> outcome = Outcome.fail("myapp.orders", "invalid_quantity", "Quantity must be positive");

        assertThat(outcome.isFail()).isTrue();
        Failure failure = ((Outcome.Fail<String>) outcome).failure();
        assertThat(failure.id().namespace()).isEqualTo("myapp.orders");
        assertThat(failure.id().name()).isEqualTo("invalid_quantity");
        assertThat(failure.message()).isEqualTo("Quantity must be positive");
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void fail_withClassNameAndMessage_usesPackageNameAsNamespace() {
        Outcome<String> outcome = Outcome.fail(OutcomeTest.class, "test_error", "Something went wrong");

        assertThat(outcome.isFail()).isTrue();
        Failure failure = ((Outcome.Fail<String>) outcome).failure();
        assertThat(failure.id().namespace()).isEqualTo("org.javai.outcome");
        assertThat(failure.id().name()).isEqualTo("test_error");
        assertThat(failure.message()).isEqualTo("Something went wrong");
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void patternMatching_works() {
        Outcome<String> ok = Outcome.ok("hello");
        Outcome<String> fail = Outcome.fail(createTestFailure("error"));

        String okResult = switch (ok) {
            case Outcome.Ok<String> o -> "got: " + o.value();
            case Outcome.Fail<String> f -> "failed: " + f.failure().message();
        };

        String failResult = switch (fail) {
            case Outcome.Ok<String> o -> "got: " + o.value();
            case Outcome.Fail<String> f -> "failed: " + f.failure().message();
        };

        assertThat(okResult).isEqualTo("got: hello");
        assertThat(failResult).isEqualTo("failed: error");
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
