package org.javai.outcome;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FailureTest {

    private static final FailureId TEST_ID = FailureId.of("test", "error");

    @Test
    void exception_returnsTypedExceptionWhenMatching() {
        IOException cause = new IOException("connection reset");
        Failure failure = Failure.transientFailure(TEST_ID, "failed", "op", cause);

        Optional<IOException> result = failure.exception(IOException.class);

        assertThat(result).containsSame(cause);
    }

    @Test
    void exception_returnsEmptyWhenTypeDoesNotMatch() {
        IOException cause = new IOException("connection reset");
        Failure failure = Failure.transientFailure(TEST_ID, "failed", "op", cause);

        Optional<IllegalArgumentException> result = failure.exception(IllegalArgumentException.class);

        assertThat(result).isEmpty();
    }

    @Test
    void exception_returnsEmptyWhenNoExceptionPresent() {
        Failure failure = Failure.transientFailure(TEST_ID, "failed", "op", null);

        Optional<IOException> result = failure.exception(IOException.class);

        assertThat(result).isEmpty();
    }

    @Test
    void exception_matchesSubclass() {
        SocketTimeoutException cause = new SocketTimeoutException("timed out");
        Failure failure = Failure.transientFailure(TEST_ID, "failed", "op", cause);

        assertThat(failure.exception(IOException.class)).containsSame(cause);
        assertThat(failure.exception(SocketTimeoutException.class)).containsSame(cause);
    }
}