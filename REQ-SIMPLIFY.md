# REQ-SIMPLIFY: Failure Type Hierarchy Simplification

## Current State

The failure handling system comprises **9 types** arranged in a deep hierarchy:

```
Failure (record, 7 fields)
├── kind: FailureKind (record, 6 fields)
│   ├── code: FailureCode (record, 2 fields)
│   ├── message: String
│   ├── category: FailureCategory (enum: RECOVERABLE, DEFECT, TERMINAL)
│   ├── stability: FailureStability (enum: TRANSIENT, PERMANENT, UNKNOWN)
│   ├── retryHint: RetryHint (record, 4 fields)
│   │   └── retryability: Retryability (enum: NONE, MAYBE, YES)
│   └── cause: Cause (record, 3 fields)
├── operation: String
├── occurredAt: Instant
├── correlationId: String?
├── tags: Map<String, String>
├── notificationIntent: NotificationIntent (enum: NONE, OBSERVE, ALERT, PAGE)
└── trackingId: String
```

## Problems

1. **No exception access**: `Failure` captures `Cause` (a summary) but not the original `Throwable`. Diagnostics and logging often need the full exception with its stack trace.

2. **Excessive indirection**: To get the error code, one must traverse `failure.kind().code().toString()`. Convenience methods help but don't eliminate the cognitive load of understanding the model.

3. **Redundant concepts**:
   - `FailureCategory` and `FailureStability` are nearly always used together to determine retry/notification behavior
   - `Retryability` (NONE/MAYBE/YES) largely duplicates what `FailureStability` (TRANSIENT/PERMANENT) already expresses
   - `RetryHint` has 4 fields when most uses need only a delay duration

4. **Derived values stored as fields**: `NotificationIntent` is computed from category+stability. Storing it means potential inconsistency.

5. **Learning curve**: A developer must understand 9 types before they can reason about failure handling. This is a barrier to adoption.

## Design Goals

1. A developer should understand the failure model in minutes, not hours
2. Common cases should be trivial; advanced cases should be possible
3. The original exception must be accessible when needed
4. Retry and notification behavior should be obvious from the failure itself

## Proposed Simplification

### Core Concept: One Failure Type

Collapse the hierarchy into a single `Failure` record with everything needed:

```java
public record Failure(
    String code,           // e.g., "network:timeout", "io:file_not_found"
    String message,        // Human-readable description
    FailureType type,      // TRANSIENT, PERMANENT, or DEFECT
    Throwable exception,   // The original exception (nullable)
    Duration retryAfter,   // Suggested retry delay (null = don't retry)
    String operation,      // What operation failed
    Instant occurredAt,    // When it happened
    String correlationId,  // Trace correlation (nullable)
    Map<String, String> tags  // Additional metadata
) { }
```

### Supporting Type: FailureType (enum)

Combines category and stability into a single concept:

```java
public enum FailureType {
    /** Temporary issue that may resolve on retry (network blip, service restarting) */
    TRANSIENT,

    /** Permanent issue that won't resolve on retry (bad input, resource deleted) */
    PERMANENT,

    /** Programming error or misconfiguration requiring developer intervention */
    DEFECT
}
```

### Derived Behavior

**Retry decision**: Simple rule based on `type` and `retryAfter`:
- `TRANSIENT` + non-null `retryAfter` → retry after delay
- `TRANSIENT` + null `retryAfter` → retry with default backoff
- `PERMANENT` or `DEFECT` → don't retry

**Notification intent**: Computed method, not stored:
```java
public NotificationIntent notificationIntent() {
    return switch (type) {
        case TRANSIENT -> NotificationIntent.OBSERVE;
        case PERMANENT -> NotificationIntent.ALERT;
        case DEFECT -> NotificationIntent.ALERT;
    };
}
```

### What's Eliminated

| Removed                 | Replacement                        |
|-------------------------|------------------------------------|
| `FailureKind` record    | Fields merged into `Failure`       |
| `FailureCode` record    | Simple `String code` field         |
| `FailureCategory` enum  | Merged into `FailureType`          |
| `FailureStability` enum | Merged into `FailureType`          |
| `RetryHint` record      | Simple `Duration retryAfter` field |
| `Retryability` enum     | Derived from `type`                |
| `Cause` record          | Direct `Throwable exception` field |

**Result: 9 types → 2 types** (Failure + FailureType)

### Factory Methods

Semantic constructors for common cases:

```java
// Network timeout - transient, should retry
Failure.transient("network:timeout", "Connection timed out", exception, operation)

// File not found - permanent, don't retry
Failure.permanent("io:file_not_found", "File does not exist: " + path, exception, operation)

// Null pointer - defect, bug in code
Failure.defect("defect:null_pointer", "Unexpected null", exception, operation)

// Rate limited - transient with specific delay
Failure.transient("http:rate_limited", "Too many requests", exception, operation)
    .withRetryAfter(Duration.ofSeconds(30))
```

### Migration Considerations

1. **Classifiers** become simpler - return `Failure` directly instead of `FailureKind`
2. **Boundary** still enriches with operation/correlation/tags but works with one type
3. **RetryPolicy** checks `failure.type()` and `failure.retryAfter()` directly
4. **Existing code** using convenience accessors (`failure.code()`, `failure.message()`) continues to work

## Open Questions

1. **Should `code` remain structured?** The string "network:timeout" loses type safety but gains simplicity. Alternative: keep `FailureCode` as a simple wrapper for namespace+name validation.

2. **Is TERMINAL still needed?** Currently `FailureCategory.TERMINAL` handles OOM/StackOverflow. These are JVM-fatal and arguably shouldn't go through the failure system at all. Propose: remove TERMINAL, let these propagate as uncaught exceptions.

3. **Tags vs. exception metadata?** With the exception available, do we still need tags? Propose: keep tags for structured observability data that isn't in the exception.

## Recommendation

Proceed with the simplification. The current design optimizes for flexibility at the cost of usability. Most applications need:
- To know what failed (code + message)
- To know if they should retry (type + retryAfter)
- To have diagnostic information (exception)

The proposed model provides exactly this with minimal ceremony.
