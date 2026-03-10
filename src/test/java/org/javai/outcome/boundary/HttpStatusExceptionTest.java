package org.javai.outcome.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpStatusExceptionTest {

    @Test
    void constructor_setsStatusCodeAndMessage() {
        HttpStatusException exception = new HttpStatusException(429, "Too Many Requests");

        assertThat(exception.statusCode()).isEqualTo(429);
        assertThat(exception.getMessage()).isEqualTo("Too Many Requests");
        assertThat(exception.retryAfter()).isNull();
    }

    @Test
    void constructor_setsRetryAfter() {
        Duration retryAfter = Duration.ofSeconds(30);
        HttpStatusException exception = new HttpStatusException(429, "Too Many Requests", retryAfter);

        assertThat(exception.statusCode()).isEqualTo(429);
        assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void parseRetryAfter_seconds() {
        assertThat(HttpStatusException.parseRetryAfter("120")).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void parseRetryAfter_zeroSeconds() {
        assertThat(HttpStatusException.parseRetryAfter("0")).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_nullReturnsNull() {
        assertThat(HttpStatusException.parseRetryAfter(null)).isNull();
    }

    @Test
    void parseRetryAfter_blankReturnsNull() {
        assertThat(HttpStatusException.parseRetryAfter("  ")).isNull();
    }

    @Test
    void parseRetryAfter_emptyReturnsNull() {
        assertThat(HttpStatusException.parseRetryAfter("")).isNull();
    }

    @Test
    void parseRetryAfter_negativeReturnsNull() {
        assertThat(HttpStatusException.parseRetryAfter("-1")).isNull();
    }

    @Test
    void parseRetryAfter_unparsableReturnsNull() {
        assertThat(HttpStatusException.parseRetryAfter("not-a-value")).isNull();
    }

    @Test
    void parseRetryAfter_httpDateFormat() {
        // HTTP-date in the past should return Duration.ZERO
        Duration result = HttpStatusException.parseRetryAfter("Mon, 01 Jan 2024 00:00:00 GMT");
        assertThat(result).isEqualTo(Duration.ZERO);
    }

    @Test
    void parseRetryAfter_trims_whitespace() {
        assertThat(HttpStatusException.parseRetryAfter("  60  ")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void isCheckedException() {
        HttpStatusException exception = new HttpStatusException(500, "error");
        assertThat(exception).isInstanceOf(Exception.class);
        assertThat(exception).isNotInstanceOf(RuntimeException.class);
    }
}
