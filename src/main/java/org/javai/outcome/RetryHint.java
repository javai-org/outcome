package org.javai.outcome;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Advisory metadata guiding retry behavior.
 * This is a hint, not a command—policies may override.
 *
 * @param retryability Whether retry is recommended
 * @param minDelay Suggested minimum delay before retry (may be null)
 * @param notBefore Do not retry before this instant (may be null, e.g., for rate limits)
 * @param reasonCode Stable token explaining why (e.g., "rate_limited", "server_overloaded")
 */
public record RetryHint(
        Retryability retryability,
        Duration minDelay,
        Instant notBefore,
        String reasonCode
) {

    public RetryHint {
        Objects.requireNonNull(retryability, "retryability must not be null");
    }

    public static RetryHint none() {
        return new RetryHint(Retryability.NONE, null, null, null);
    }

    public static RetryHint yes() {
        return new RetryHint(Retryability.YES, null, null, null);
    }

    public static RetryHint withDelay(Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        return new RetryHint(Retryability.YES, delay, null, null);
    }

    public static RetryHint notBefore(Instant instant, String reason) {
        Objects.requireNonNull(instant, "instant must not be null");
        return new RetryHint(Retryability.YES, null, instant, reason);
    }

    public static RetryHint maybe(String reason) {
        return new RetryHint(Retryability.MAYBE, null, null, reason);
    }
}
