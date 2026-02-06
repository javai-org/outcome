# Outcome

**A bridge between deterministic and non-deterministic worlds.**

Most application code is deterministic. Given the same inputs, it produces the same outputs. But applications routinely encounter non-determinism — operations whose outcomes depend on factors outside the code's control: network availability, service responsiveness, database state, or even intentional randomness in stochastic algorithms.

Not all non-determinism implies failure. A random number generator is non-deterministic yet doesn't fail. However, in practice, the vast majority of non-deterministic operations Java developers encounter are *fallible*: network calls, database queries, external API invocations. These may timeout, return errors, or simply not respond. Outcome addresses this dominant case.

It provides a formal boundary where deterministic code meets fallible operations. At this boundary, uncertainty is acknowledged, observed, and converted into one of two well-defined states: **Ok** or **Fail**. From there, application code flows deterministically again — handling success or failure through normal control flow, not exception handling.

## The Problem

Java's exception model conflates three fundamentally different things:

1. **Operational failures** — network timeouts, service unavailability, rate limits. These are *normal*. They happen every day in production. They're expected, recoverable, and often transient.

2. **Defects** — null pointers, invalid arguments, misconfiguration. These are *bugs*. No retry will help. A human must fix the code or the configuration.

3. **Terminal environment failures** — `OutOfMemoryError`, `StackOverflowError`, `NoClassDefFoundError`. The JVM itself is compromised. No application-level handling is possible or advisable.

By treating the first two as "exceptions," Java's type system forces a syntactic ritual (`try`/`catch`) that *looks* like handling but usually isn't. Developers comply with the compiler, not with operational reality:

```java
try {
    return httpClient.send(request);
} catch (IOException e) {
    log.error("Request failed", e);  // Now what?
    throw new RuntimeException(e);   // Punt.
}
```

The result:
- Inconsistent error handling across call sites
- Ad-hoc logging as a substitute for proper operator notification
- Bespoke retry loops scattered through the codebase
- No standardized semantics for reporting, aggregation, or retry guidance

## The Solution

**Operational failures are values.** They belong in normal control flow, not exception handling.

```java
Outcome<Response> result = boundary.call("UserApi.fetch", () -> httpClient.send(request));

return switch (result) {
    case Outcome.Ok(var response) -> processResponse(response);
    case Outcome.Fail(var failure) -> handleFailure(failure);
};
```

**Defects remain exceptions.** A `NullPointerException` should crash the operation and page an operator. No `catch` block will fix a bug.

**Terminal errors are not handled.** An `OutOfMemoryError` means the JVM is compromised. The framework does not catch `Error`—let the process die and let your infrastructure (Kubernetes, systemd, supervisors) restart it.

This isn't a new idea—it's how Rust, Go, and functional languages handle errors. Outcome brings this proven idiom to Java with full type safety and pattern matching support.

## Core Concepts

### Outcome&lt;T&gt;

A sealed interface representing success or failure:

```java
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Fail {
    record Ok<T>(T value) implements Outcome<T> {}
    record Fail<T>(Failure failure) implements Outcome<T> {}
}
```

Use pattern matching for exhaustive handling:

```java
String message = switch (outcome) {
    case Outcome.Ok(var user) -> "Hello, " + user.name();
    case Outcome.Fail(var failure) -> "Error: " + failure.message();
};
```

Or use combinators for functional composition:

```java
Outcome<Order> order = fetchUser(userId)
    .flatMap(user -> fetchCart(user.cartId()))
    .flatMap(cart -> createOrder(cart));
```

### Failure

A structured failure with everything needed for reporting and policy decisions:

- **FailureId** — namespaced identifier (`network:timeout`, `sql:connection`)
- **FailureType** — `TRANSIENT`, `PERMANENT`, or `DEFECT`
- **RetryAfter** — advisory delay before retry (e.g., from HTTP 429/503 `Retry-After` header)
- **Operation** — the operation that failed
- **Tags** — additional key-value metadata for observability

This isn't heavyweight ceremony—it's the information operators *need* to respond appropriately.

### Boundary

The single point where checked exceptions are translated into outcomes:

```java
Boundary boundary = new Boundary(classifier, reporter);

Outcome<Response> result = boundary.call("HttpClient.send", () ->
    httpClient.send(request)  // throws IOException
);
```

- Checked exceptions → `Outcome.Fail` (reported to operations)
- Unchecked exceptions → propagate (caught by `OperationalExceptionHandler` at the top)

The Boundary ensures checked exceptions never leak into application code while maintaining full observability.

### Operator Reporting

Failures are first-class concerns, not afterthoughts buried in log files:

```java
public interface OpReporter {
    void report(Failure failure);
    void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay);
    void reportRetryExhausted(Failure failure, int totalAttempts);
}
```

OpReporter implementations decide how to handle each failure based on its `FailureType` and their own configuration. Route to your observability stack—metrics, structured logging, Slack, Teams, or whatever your organization uses.

### Policy-Driven Retry

Retry logic belongs in policies, not scattered `catch` blocks:

```java
Retrier retrier = Retrier.builder()
    .policy(RetryPolicy.backoff(5, Duration.ofMillis(100), Duration.ofSeconds(10)))
    .reporter(reporter)
    .build();

Outcome<Response> result = retrier.execute(
    () -> boundary.call("UserApi.fetch", () -> userApi.fetch(userId))
);
```

Policies respect the failure's `RetryHint` and report all attempts through `OpReporter`.

### Guided Retry

For scenarios where failures can inform subsequent attempts—such as LLM interactions where error context helps the model self-correct—use guided retry:

```java
Outcome<Order> result = Retrier.withGuidance(4)
    .attempt(() -> parse(llm.chat(request)))
    .deriveGuidance(failure -> "\n\nPrevious attempt failed: " + failure.message() + "\nPlease try again.")
    .reattempt(guidance -> () -> parse(llm.chat(request + guidance)))
    .execute();
```

The pattern:
1. **attempt** — the initial work to execute
2. **deriveGuidance** — converts a failure into guidance text
3. **reattempt** — the work to execute on retries, given the derived guidance

This enables feedback loops where each failure provides context for the next attempt. Optional `policy()` and `reporter()` configuration is available:

```java
Outcome<Order> result = Retrier.withGuidance(4)
    .policy(RetryPolicy.fixed(4, Duration.ofSeconds(1)))
    .reporter(customReporter)
    .attempt(() -> parse(llm.chat(request)))
    .deriveGuidance(failure -> extractValidationErrors(failure))
    .reattempt(guidance -> () -> parse(llm.chat(request + guidance)))
    .execute();
```

## The Full Picture

```
                         ┌───────────────────────────────────┐
                         │  Error (OOME, StackOverflow)      │
                         │  - NOT CAUGHT                     │
                         │  - JVM/thread terminates          │
                         │  - Infrastructure restarts        │
                         └───────────────────────────────────┘
                                          ↑
                                    propagates
                                          │
┌─────────────────────────────────────────────────────────────────┐
│  OperationalExceptionHandler (top of stack)                     │
│  - Catches uncaught RuntimeExceptions (defects)                 │
│  - Reports to OpReporter with PAGE intent                       │
│  - Thread terminates                                            │
└─────────────────────────────────────────────────────────────────┘
                              ↑
                      propagates (uncaught)
                              │
┌─────────────────────────────────────────────────────────────────┐
│  Application Code                                               │
│  - Works with Outcome<T> in normal control flow                 │
│  - Pattern matching for explicit handling                       │
│  - Combinators for composition (map, flatMap, recover)          │
└─────────────────────────────────────────────────────────────────┘
                              ↑
               Outcome.Fail (checked) or throw (unchecked/Error)
                              │
┌─────────────────────────────────────────────────────────────────┐
│  Boundary                                                       │
│  - Checked exception → classify → report → Outcome.Fail         │
│  - RuntimeException → rethrow (defect, not recoverable)         │
│  - Error → rethrow (terminal, not handleable)                      │
└─────────────────────────────────────────────────────────────────┘
                              ↑
                     third-party API throws
                              │
┌─────────────────────────────────────────────────────────────────┐
│  Third-Party Code (JDBC, HTTP clients, SDKs)                    │
│  - Throws checked exceptions                                    │
└─────────────────────────────────────────────────────────────────┘
```

## Package Structure

```
org.javai.outcome
├── Outcome, Failure, FailureKind, FailureCode
├── FailureType, FailureId
├── RetryHint, Retryability, Cause
│
├── boundary/
│   ├── Boundary, ThrowingSupplier
│   ├── FailureClassifier, BoundaryFailureClassifier
│
├── retry/
│   ├── Retrier, RetryPolicy
│   ├── RetryDecision, RetryContext
│
└── ops/
    ├── OpReporter, DefectClassifier
    └── OperationalExceptionHandler
```

## Getting Started

```java
// 1. Set up the infrastructure
OpReporter reporter = failure -> System.out.println("FAILURE: " + failure);

Boundary boundary = new Boundary(new BoundaryFailureClassifier(), reporter);
Retrier retrier = new Retrier(reporter);

// 2. Install the uncaught exception handler
new OperationalExceptionHandler(new DefectClassifier(), reporter).installAsDefault();

// 3. Use Outcome in your code
Outcome<User> result = boundary.call("UserService.findById", () ->
    userRepository.findById(id)
);

return switch (result) {
    case Outcome.Ok(var user) -> ResponseEntity.ok(user);
    case Outcome.Fail(var f) -> ResponseEntity.status(503).body(f.message());
};
```

## Philosophy

### The Boundary Principle

Software systems are composed of two fundamentally different domains:

**Deterministic code** — your application logic. Given the same inputs, it produces the same outputs. It can be reasoned about, tested exhaustively, and trusted to behave predictably.

**Fallible operations** — databases, APIs, networks, external systems. They may succeed, fail, timeout, return garbage, or simply not respond. No amount of careful coding eliminates this uncertainty.

Outcome's central insight is that **the boundary between these domains deserves first-class treatment**. The `Boundary` component marks these crossing points explicitly. Uncertainty is acknowledged, observed, and resolved into a well-defined `Outcome` — either `Ok` or `Fail`. From there, deterministic code resumes.

This isn't defensive programming. It's architectural honesty.

### Failure Modes

Different failure modes deserve different treatment:

| Category        | Examples                                       | Handling                             | Recovery                            |
|-----------------|------------------------------------------------|--------------------------------------|-------------------------------------|
| **Recoverable** | Timeouts, rate limits, unavailable services    | `Outcome.Fail` — normal control flow | Retry, fallback, degrade gracefully |
| **Defect**      | NullPointerException, IllegalArgumentException | Propagate, page operator             | Human fixes code/config             |
| **Fatal**       | OutOfMemoryError, StackOverflowError           | Don't catch                          | Infrastructure restarts process     |

A socket timeout isn't exceptional — it's Tuesday. The network is unreliable by nature. Treating timeouts as exceptions is like treating rain as an exception to weather.

### What We Gain

By representing expected failures as data:

- **Composition** — failures flow through `flatMap` and `recover`
- **Type safety** — the compiler ensures handling
- **Consistency** — one failure model across the codebase
- **Observability** — structured reporting at every boundary crossing

Defects (unchecked exceptions) propagate to the top of the stack where `OperationalExceptionHandler` pages an operator. Terminal errors (`Error` subclasses) are never caught — the JVM is compromised, and the only sensible response is to let the process die.

### The Outcome Principle

**Recoverable failures flow as values. Defects crash and page. Terminal errors terminate.**

## Requirements

- Java 21+

## License

Attribution Required License (ARL-1.0) - see [LICENSE](LICENSE) for details.
