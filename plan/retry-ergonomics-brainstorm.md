# Retry Ergonomics Brainstorm

## Context

Exploring improvements to the Outcome framework's retry handling to:
1. Reduce ceremony when using retries
2. Support "corrective retry" where failure information feeds back into subsequent attempts

## Current State

The existing machinery:
- **Boundary**: Catches checked exceptions, classifies them via `FailureClassifier`, returns `Outcome<T>`
- **Retrier**: Wraps a `Supplier<Outcome<T>>` and applies retry policy
- **RetryPolicy**: Decides whether/when to retry based on failure characteristics

Current usage requires significant ceremony:
```java
OpReporter reporter = ...;
Boundary boundary = Boundary.withReporter(reporter);
Retrier retrier = new Retrier(reporter);
RetryPolicy policy = RetryPolicy.exponentialBackoff("id", 3, Duration.ofMillis(100), Duration.ofSeconds(5));

Outcome<String> result = retrier.execute("FetchUser", policy, boundary, () -> userApi.fetch(userId));
```

## Decision: No New RetryableOutcome Type Needed

Initially considered introducing `RetryableOutcome<T>` to force callers to handle transient failures at compile time. After discussion, concluded that:

- The existing `FailureStability` (TRANSIENT, PERMANENT, UNKNOWN) already captures this at runtime
- The `Retrier` provides the "handle retries" path when needed
- Adding a parallel type hierarchy introduces complexity without sufficient benefit
- Better to improve ergonomics of the existing pattern

The two calling patterns are already supported:
1. **Direct call** (no retries): `boundary.call("op", () -> work)` → `Outcome<T>`
2. **Via Retrier** (with retries): `retrier.execute("op", policy, () -> ...)` → `Outcome<T>`

---

## Proposal 1: Static Convenience Methods on Retrier

Reduce ceremony with static methods that handle common cases.

### Minimal Ceremony (Testing/Prototyping)

Silent reporter, default boundary, sensible policy defaults:

```java
// Exponential backoff, 3 attempts, 100ms initial delay
Outcome<String> result = Retrier.attempt(3, () -> userApi.fetch(userId));

// With custom initial delay
Outcome<String> result = Retrier.attempt(3, Duration.ofMillis(200), () -> userApi.fetch(userId));

// Single retry
Outcome<String> result = Retrier.once(() -> apiCall());
```

### Production Usage (With Reporting)

```java
// Reporter + attempt count (auto-generates policy)
Outcome<String> result = Retrier.attempt("UserApi.fetch", reporter, 3, () -> userApi.fetch(userId));

// Full control
Outcome<String> result = Retrier.attempt("UserApi.fetch", reporter, policy, () -> userApi.fetch(userId));
```

### Preset Patterns

```java
Outcome<String> result = Retrier.withFixedDelay(3, Duration.ofMillis(100), () -> work);
Outcome<String> result = Retrier.withBackoff(3, () -> work);  // Exponential
```

### Preconditions

- `maxAttempts` must be > 0. A value of 0 is rejected with `IllegalArgumentException`.
- `maxAttempts = 1` means a single attempt with no retries.
- `maxAttempts = 2` means one attempt plus one retry, etc.

### Implementation Sketch

```java
public final class Retrier {
    // ... existing instance methods ...

    // === STATIC CONVENIENCE METHODS ===

    private static void requireValidAttempts(int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0, was: " + maxAttempts);
        }
    }

    /**
     * Silent retry with exponential backoff - for prototyping/testing.
     */
    public static <T> Outcome<T> attempt(
            int maxAttempts,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        requireValidAttempts(maxAttempts);
        return attempt(maxAttempts, Duration.ofMillis(100), work);
    }

    /**
     * Silent retry with exponential backoff and custom initial delay.
     */
    public static <T> Outcome<T> attempt(
            int maxAttempts,
            Duration initialDelay,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        requireValidAttempts(maxAttempts);
        Boundary boundary = Boundary.silent();
        Retrier retrier = new Retrier(OpReporter.noOp());
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
            "attempt", maxAttempts, initialDelay, initialDelay.multipliedBy(32)
        );
        return retrier.execute("attempt", policy, boundary, work);
    }

    /**
     * Production retry with reporting.
     */
    public static <T> Outcome<T> attempt(
            String operation,
            OpReporter reporter,
            RetryPolicy policy,
            ThrowingSupplier<T, ? extends Exception> work
    ) {
        Boundary boundary = Boundary.withReporter(reporter);
        Retrier retrier = new Retrier(reporter);
        return retrier.execute(operation, policy, boundary, work);
    }

    /**
     * Single retry attempt.
     */
    public static <T> Outcome<T> once(ThrowingSupplier<T, ? extends Exception> work) {
        return attempt(2, work);  // 2 attempts = 1 original + 1 retry
    }
}
```

---

## Proposal 2: Corrective Retry (Feedback Loop)

A more sophisticated retry pattern where the failure from the previous attempt is passed back into the next attempt. This enables self-correction rather than blind repetition.

### Use Case: LLM Generating JSON

```
Attempt 1: "Generate JSON for order #123"
→ LLM returns: { "orderId": 123, "items": [...] (malformed - missing closing brace)
→ Failure: JSON parse error at position 47

Attempt 2: "Generate JSON for order #123.
            Previous attempt failed: 'Unexpected end of input at position 47'.
            Please ensure valid JSON syntax."
→ LLM returns: valid JSON
→ Success
```

### API Options

#### Option A: Nullable Previous Failure

Simple and direct:

```java
Outcome<Order> result = Retrier.attemptWithFeedback(3, lastFailure -> {
    String prompt = buildPrompt(orderId);
    if (lastFailure != null) {
        prompt += "\n\nYour previous response failed: " + lastFailure.message()
                + ". Please correct and try again.";
    }
    return llm.generate(prompt, Order.class);
});
```

#### Option B: Dedicated Functional Interface

More explicit typing:

```java
@FunctionalInterface
public interface CorrectiveOperation<T> {
    T attempt(@Nullable Failure lastFailure) throws Exception;
}

Outcome<Order> result = Retrier.corrective(3, lastFailure -> {
    return llm.generateWithCorrection(orderId, lastFailure);
});
```

#### Option C: Full Context Object

Access to attempt number and complete failure history:

```java
public interface RetryAttemptContext {
    int attemptNumber();
    Optional<Failure> lastFailure();
    List<Failure> allPreviousFailures();  // Full history for pattern detection
}

Outcome<Order> result = Retrier.withContext(3, ctx -> {
    if (ctx.attemptNumber() == 1) {
        return llm.generate(orderId);
    } else {
        return llm.retry(orderId, ctx.lastFailure().orElseThrow());
    }
});
```

### Implementation Sketch (Option A)

```java
public final class Retrier {
    // ... existing methods ...

    /**
     * Executes with corrective feedback - previous failure passed to each retry.
     */
    public <T> Outcome<T> executeWithFeedback(
            String operation,
            RetryPolicy policy,
            Function<Failure, Outcome<T>> attempt
    ) {
        RetryContext context = RetryContext.first();
        Failure lastFailure = null;
        Outcome<T> result = attempt.apply(null);  // First attempt, no previous failure

        while (result instanceof Outcome.Fail<T> fail) {
            lastFailure = fail.failure();
            RetryDecision decision = policy.decide(context, lastFailure);

            if (decision instanceof RetryDecision.GiveUp) {
                reporter.reportRetryExhausted(lastFailure, context.attemptNumber(), policy.id());
                return result;
            }

            if (decision instanceof RetryDecision.Retry retry) {
                reporter.reportRetryAttempt(lastFailure, context.attemptNumber(), policy.id());
                sleep(retry.delay());
                context = context.next();
                result = attempt.apply(lastFailure);  // Pass failure to next attempt
            }
        }

        return result;
    }

    // Static convenience
    public static <T> Outcome<T> attemptWithFeedback(
            int maxAttempts,
            Function<Failure, ThrowingSupplier<T, ? extends Exception>> work
    ) {
        Boundary boundary = Boundary.silent();
        Retrier retrier = new Retrier(OpReporter.noOp());
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
            "corrective", maxAttempts, Duration.ofMillis(100), Duration.ofSeconds(5)
        );
        return retrier.executeWithFeedback(
            "corrective",
            policy,
            failure -> boundary.call("corrective", work.apply(failure))
        );
    }
}
```

### Two Sources of Feedback

Corrective retry can receive feedback from two distinct sources:

#### 1. Service-Level Feedback

Feedback originates from the service being called. The operation throws an exception or returns an error that gets wrapped into a `Failure`:

```java
// Service returns error → becomes Failure
Outcome<Order> result = Retrier.attemptWithFeedback(3, lastFailure -> {
    String prompt = buildPrompt(orderId);
    if (lastFailure != null) {
        // Failure came from JSON parse exception, HTTP error, etc.
        prompt += "\nPrevious error: " + lastFailure.message();
    }
    return llm.generate(prompt, Order.class);  // May throw JsonParseException
});
```

Examples: JSON parse errors, HTTP 4xx/5xx, schema validation failures, timeout exceptions.

#### 2. Recipient-Level Feedback

The recipient (caller) receives the `Failure` and may want to interpret, transform, or enrich it before it feeds into the retry:

```java
// Recipient intercepts the failure and provides custom feedback
Outcome<Order> result = Retrier.attemptWithFeedback(3,
    work: previousFeedback -> {
        String prompt = buildPrompt(orderId);
        if (previousFeedback != null) {
            prompt += "\n" + previousFeedback;
        }
        return llm.generate(prompt, Order.class);
    },
    failureInterpreter: failure -> {
        // Recipient interprets the failure before retry
        if (failure.code().equals("RATE_LIMITED")) {
            return "The service is busy. Please generate a shorter response.";
        }
        if (failure.message().contains("token limit")) {
            return "Response was too long. Be more concise.";
        }
        // Return null if no feedback to give - retry proceeds without feedback
        return null;
    }
);
```

If the `failureInterpreter` returns `null`, the retry proceeds without any feedback—equivalent to a standard retry with no corrective context. This is valid when the recipient has no useful interpretation to offer for a particular failure.

**Examples of recipient-level interpretation:**
- Transforming technical errors into domain-specific guidance (e.g., "token limit exceeded" → "be more concise")
- Adding context from the caller's domain knowledge
- Simplifying or enriching error messages for the retry context
- Stripping sensitive information before feeding back
- Returning `null` for failures where feedback wouldn't help (e.g., transient network errors)

#### Key Distinction

| Source    | When feedback is generated             | Who controls the feedback         |
|-----------|----------------------------------------|-----------------------------------|
| Service   | During the operation (exception/error) | The service being called          |
| Recipient | After receiving the Failure            | The caller of the retry mechanism |

The recipient-level feedback gives callers control over how failures are interpreted and communicated back into the retry loop, rather than passing the raw service error directly.

---

### Failure Content for LLM Correction

The `Failure` object passed to corrective retries should carry:

- `message()` - Human-readable error ("Expected '}' at position 47")
- `cause()` - Underlying exception for programmatic inspection
- `code()` - Structured error code for categorization
- Custom metadata via tags - e.g., the raw LLM response that failed validation

For LLM use cases, consider extending with:
```java
// In the classifier or Boundary, attach the problematic response
Map<String, String> tags = Map.of(
    "raw_response", llmResponse,
    "validation_errors", errors.toString()
);
```

---

## Summary

| Feature                    | Benefit                                     |
|----------------------------|---------------------------------------------|
| Static convenience methods | Reduce ceremony for common cases            |
| Silent defaults            | Quick prototyping without boilerplate       |
| Corrective retry           | Enable self-correction via failure feedback |
| Optional reporter          | Production observability when needed        |

### Recommended Next Steps

1. Add static convenience methods to `Retrier` for minimal-ceremony usage
2. Add `executeWithFeedback` / `attemptWithFeedback` for corrective retry pattern
3. Consider whether `Failure` needs additional fields for carrying context (e.g., raw response)