package org.javai.outcome.boundary;

import org.javai.outcome.*;
import org.javai.outcome.ops.OpReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class BoundaryTest {

    private Boundary boundary;
    private List<Failure> reportedFailures;

    @BeforeEach
    void setUp() {
        reportedFailures = new ArrayList<>();
        OpReporter reporter = reportedFailures::add;
        boundary = new Boundary(new BoundaryFailureClassifier(), reporter);
    }

    @Test
    void call_success_returnsOk() {
        Outcome<String> result = boundary.call("TestOp", () -> "success");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getOrThrow()).isEqualTo("success");
        assertThat(reportedFailures).isEmpty();
    }

    @Test
    void call_checkedException_returnsFail() {
        Outcome<String> result = boundary.call("TestOp", () -> {
            throw new IOException("disk error");
        });

        assertThat(result.isFail()).isTrue();
        assertThat(reportedFailures).hasSize(1);
    }

    @Test
    void call_runtimeException_propagates() {
        assertThatThrownBy(() -> boundary.call("TestOp", () -> {
            throw new RuntimeException("defect");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("defect");

        // Not reported via Boundary—will be caught by UncaughtExceptionHandler
        assertThat(reportedFailures).isEmpty();
    }

    @Test
    void call_illegalArgumentException_propagates() {
        assertThatThrownBy(() -> boundary.call("TestOp", () -> {
            throw new IllegalArgumentException("bad arg");
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad arg");

        assertThat(reportedFailures).isEmpty();
    }

    @Test
    void call_nullPointerException_propagates() {
        assertThatThrownBy(() -> boundary.call("TestOp", () -> {
            throw new NullPointerException("oops");
        }))
                .isInstanceOf(NullPointerException.class);

        assertThat(reportedFailures).isEmpty();
    }

    @Test
    void call_classifiesSocketTimeout_asTransient() {
        Outcome<String> result = boundary.call("HttpCall", () -> {
            throw new SocketTimeoutException("timed out");
        });

        assertThat(result.isFail()).isTrue();
        Failure failure = ((Outcome.Fail<String>) result).failure();

        assertThat(failure.code()).isEqualTo(FailureCode.of("network", "timeout"));
        assertThat(failure.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(failure.stability()).isEqualTo(FailureStability.TRANSIENT);
        assertThat(failure.operation()).isEqualTo("HttpCall");
    }

    @Test
    void call_reportsCheckedExceptionFailure() {
        boundary.call("FailingOp", () -> {
            throw new IOException("disk error");
        });

        assertThat(reportedFailures).hasSize(1);
        Failure reported = reportedFailures.getFirst();
        assertThat(reported.operation()).isEqualTo("FailingOp");
    }

    @Test
    void call_withTags_includesTags() {
        Outcome<String> result = boundary.call(
                "TaggedOp",
                Map.of("service", "user-api", "region", "us-east"),
                () -> { throw new IOException("error"); }
        );

        Failure failure = ((Outcome.Fail<String>) result).failure();
        assertThat(failure.tags())
                .containsEntry("service", "user-api")
                .containsEntry("region", "us-east");
    }

    @Test
    void call_withCorrelationIdSupplier_includesCorrelationId() {
        Boundary boundaryWithCorrelation = new Boundary(
                new BoundaryFailureClassifier(),
                reportedFailures::add,
                () -> "trace-123"
        );

        Outcome<String> result = boundaryWithCorrelation.call("Op", () -> {
            throw new IOException("error");
        });

        Failure failure = ((Outcome.Fail<String>) result).failure();
        assertThat(failure.correlationId()).isEqualTo("trace-123");
    }

    @Test
    void call_setsOccurredAt() {
        Outcome<String> result = boundary.call("Op", () -> {
            throw new IOException("error");
        });

        Failure failure = ((Outcome.Fail<String>) result).failure();
        assertThat(failure.occurredAt()).isNotNull();
    }

    @Test
    void call_transientOperational_setsObserveNotification() {
        Outcome<String> result = boundary.call("Op", () -> {
            throw new SocketTimeoutException("timeout");
        });

        Failure failure = ((Outcome.Fail<String>) result).failure();
        assertThat(failure.category()).isEqualTo(FailureCategory.RECOVERABLE);
        assertThat(failure.stability()).isEqualTo(FailureStability.TRANSIENT);
        assertThat(failure.notificationIntent()).isEqualTo(NotificationIntent.OBSERVE);
    }
}
