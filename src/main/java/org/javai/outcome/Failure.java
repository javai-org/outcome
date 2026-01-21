package org.javai.outcome;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A fully-contextualized failure ready for reporting and policy evaluation.
 * The Boundary creates this by combining a FailureKind with operational context.
 *
 * @param kind The classified failure (what went wrong)
 * @param operation The operation that failed (e.g., "HttpClient.send", "OrdersApi.fetchOrder")
 * @param occurredAt When the failure happened
 * @param correlationId Trace correlation identifier
 * @param tags Additional key-value metadata for observability
 * @param notificationIntent Suggested notification intent
 * @param trackingId Stable identifier for metrics aggregation (defaults to operation if null)
 */
public record Failure(
        FailureKind kind,
        String operation,
        Instant occurredAt,
        String correlationId,
        Map<String, String> tags,
        NotificationIntent notificationIntent,
        String trackingId
) {

    public Failure {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        notificationIntent = notificationIntent == null ? NotificationIntent.OBSERVE : notificationIntent;
        trackingId = trackingId == null ? operation : trackingId;
    }

    // Convenience accessors that delegate to kind
    public FailureCode code() {
        return kind.code();
    }

    public String message() {
        return kind.message();
    }

    public FailureCategory category() {
        return kind.category();
    }

    public FailureStability stability() {
        return kind.stability();
    }

    public RetryHint retryHint() {
        return kind.retryHint();
    }

    public Cause cause() {
        return kind.cause();
    }

    /**
     * Creates a Failure with minimal context. Useful for testing or simple cases.
     */
    public static Failure of(FailureKind kind, String operation) {
        return new Failure(kind, operation, Instant.now(), null, null, null, null);
    }

    /**
     * Creates a builder for constructing Failures with full context.
     */
    public static Builder builder(FailureKind kind, String operation) {
        return new Builder(kind, operation);
    }

    public static class Builder {
        private final FailureKind kind;
        private final String operation;
        private Instant occurredAt = Instant.now();
        private String correlationId;
        private Map<String, String> tags;
        private NotificationIntent notificationIntent;
        private String trackingId;

        private Builder(FailureKind kind, String operation) {
            this.kind = Objects.requireNonNull(kind);
            this.operation = Objects.requireNonNull(operation);
        }

        public Builder occurredAt(Instant instant) {
            this.occurredAt = instant;
            return this;
        }

        public Builder correlationId(String id) {
            this.correlationId = id;
            return this;
        }

        public Builder tags(Map<String, String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder notificationIntent(NotificationIntent intent) {
            this.notificationIntent = intent;
            return this;
        }

        public Builder trackingId(String trackingId) {
            this.trackingId = trackingId;
            return this;
        }

        public Failure build() {
            return new Failure(kind, operation, occurredAt, correlationId, tags, notificationIntent, trackingId);
        }
    }
}
