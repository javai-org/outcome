# Outcome

A Java framework for treating operational failures as data, not exceptions.

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

- **FailureCode** — namespaced identifier (`network:timeout`, `sql:connection`)
- **FailureCategory** — `RECOVERABLE`, `DEFECT`, or `TERMINAL`
- **FailureStability** — `TRANSIENT`, `PERMANENT`, or `UNKNOWN`
- **RetryHint** — advisory guidance for retry policies
- **NotificationIntent** — `NONE`, `OBSERVE`, `ALERT`, or `PAGE`

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
    void reportRetryAttempt(Failure failure, int attemptNumber, String policyId);
    void reportRetryExhausted(Failure failure, int totalAttempts, String policyId);
}
```

Every failure carries a `NotificationIntent`:
- **NONE** — handled entirely by the application
- **OBSERVE** — emit metrics/traces, no active notification
- **ALERT** — notify operators during business hours
- **PAGE** — wake someone up

Implementations can route to your observability stack—metrics, structured logging, PagerDuty, whatever your organization uses.

### Policy-Driven Retry

Retry logic belongs in policies, not scattered `catch` blocks:

```java
RetryPolicy policy = RetryPolicy.exponentialBackoff(
    "api-call",
    maxAttempts: 5,
    initialDelay: Duration.ofMillis(100),
    maxDelay: Duration.ofSeconds(10)
);

Outcome<Response> result = retrier.execute("FetchUser", policy, () ->
    boundary.call("UserApi.fetch", () -> userApi.fetch(userId))
);
```

Policies respect the failure's `RetryHint` and report all attempts through `OpReporter`.

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
├── FailureCategory, FailureStability, NotificationIntent
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

## Requirements

- Java 21+

## Philosophy

This framework embodies a simple principle: **different failure modes deserve different treatment**.

| Category        | Examples                                       | Handling                             | Recovery                            |
|-----------------|------------------------------------------------|--------------------------------------|-------------------------------------|
| **Recoverable** | Timeouts, rate limits, unavailable services    | `Outcome.Fail` — normal control flow | Retry, fallback, degrade gracefully |
| **Defect**      | NullPointerException, IllegalArgumentException | Propagate, page operator             | Human fixes code/config             |
| **Fatal**       | OutOfMemoryError, StackOverflowError           | Don't catch                          | Infrastructure restarts process     |

A socket timeout isn't exceptional—it's Tuesday. The network is unreliable by nature. Treating timeouts as exceptions is like treating rain as an exception to weather.

By representing expected failures as data, we gain:
- **Composition** — failures flow through `flatMap` and `recover`
- **Type safety** — the compiler ensures handling
- **Consistency** — one failure model across the codebase
- **Observability** — structured reporting, not scattered logs

Defects (unchecked exceptions) propagate to the top of the stack where `OperationalExceptionHandler` pages an operator. Terminal errors (`Error` subclasses) are never caught—the JVM is compromised, and the only sensible response is to let the process die.

**Recoverable failures flow as values. Defects crash and page. Terminal errors terminate.**
