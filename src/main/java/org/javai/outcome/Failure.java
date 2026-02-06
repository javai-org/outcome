package org.javai.outcome;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A fully-contextualized failure ready for reporting and policy evaluation.
 *
 * @param id The failure identifier (namespace:name)
 * @param message Human-readable description
 * @param type The failure type (TRANSIENT, PERMANENT, DEFECT)
 * @param exception The underlying exception (may be null)
 * @param retryAfter Suggested delay before retry (may be null)
 * @param operation The operation that failed (e.g., "HttpClient.send", "OrdersApi.fetchOrder")
 * @param occurredAt When the failure happened
 * @param correlationId Trace correlation identifier
 * @param tags Additional key-value metadata for observability
 * @param trackingId Stable identifier for metrics aggregation (defaults to operation if null)
 */
public record Failure(
        FailureId id,
        String message,
        FailureType type,
        Throwable exception,
        Duration retryAfter,
        String operation,
        Instant occurredAt,
        String correlationId,
        Map<String, String> tags,
        String trackingId
) {

    public Failure {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        trackingId = trackingId == null ? operation : trackingId;
    }

    // === Factory methods for common failure types ===

    /**
     * Creates a transient failure that may resolve on retry.
     */
    public static Failure transientFailure(FailureId id, String message, String operation, Throwable exception) {
        return new Failure(id, message, FailureType.TRANSIENT, exception, null,
                operation, Instant.now(), null, null, null);
    }

    /**
     * Creates a transient failure with a retry delay hint.
     */
    public static Failure transientFailure(FailureId id, String message, String operation, Throwable exception, Duration retryAfter) {
        return new Failure(id, message, FailureType.TRANSIENT, exception, retryAfter,
                operation, Instant.now(), null, null, null);
    }

    /**
     * Creates a permanent failure that will not resolve on retry.
     */
    public static Failure permanentFailure(FailureId id, String message, String operation, Throwable exception) {
        return new Failure(id, message, FailureType.PERMANENT, exception, null,
                operation, Instant.now(), null, null, null);
    }

    /**
     * Creates a defect failure (programming error or misconfiguration).
     */
    public static Failure defect(FailureId id, String message, String operation, Throwable exception) {
        return new Failure(id, message, FailureType.DEFECT, exception, null,
                operation, Instant.now(), null, null, null);
    }

    /**
     * Creates a builder for constructing Failures with full context.
     */
    public static Builder builder(FailureId id, String message, FailureType type, String operation) {
        return new Builder(id, message, type, operation);
    }

    /**
     * Returns a new Failure with the specified correlationId and tags added.
     */
    public Failure withContext(String correlationId, Map<String, String> tags) {
        return new Failure(id, message, type, exception, retryAfter, operation,
                occurredAt, correlationId, tags, trackingId);
    }

    public static class Builder {
        private final FailureId id;
        private final String message;
        private final FailureType type;
        private final String operation;
        private Throwable exception;
        private Duration retryAfter;
        private Instant occurredAt = Instant.now();
        private String correlationId;
        private Map<String, String> tags;
        private String trackingId;

        private Builder(FailureId id, String message, FailureType type, String operation) {
            this.id = Objects.requireNonNull(id);
            this.message = Objects.requireNonNull(message);
            this.type = Objects.requireNonNull(type);
            this.operation = Objects.requireNonNull(operation);
        }

        public Builder exception(Throwable exception) {
            this.exception = exception;
            return this;
        }

        public Builder retryAfter(Duration retryAfter) {
            this.retryAfter = retryAfter;
            return this;
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

        public Builder trackingId(String trackingId) {
            this.trackingId = trackingId;
            return this;
        }

        public Failure build() {
            return new Failure(id, message, type, exception, retryAfter, operation,
                    occurredAt, correlationId, tags, trackingId);
        }
    }
}
