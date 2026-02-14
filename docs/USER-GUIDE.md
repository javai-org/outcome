# Outcome User Guide

A comprehensive guide to using the Outcome framework for handling non-deterministic fetures and operational failures in Java applications.

---

## Introduction

### The Problem

Most application code is deterministic. Given the same inputs, it produces the same outputs. But applications routinely encounter non-determinism — operations whose outcomes depend on factors outside the code's control: network availability, service responsiveness, database state, or external API behavior.

Java's exception model conflates three fundamentally different things:

1. **Operational failures** — network timeouts, service unavailability, rate limits. These are *normal*. They happen every day in production. They're expected, recoverable, and often transient.

2. **Defects** — null pointers, invalid arguments, misconfiguration. These are *bugs*. No retry will help. A human must fix the code or configuration.

3. **Terminal environment failures** — `OutOfMemoryError`, `StackOverflowError`. The JVM itself is compromised. No application-level handling is possible.

By treating operational failures as "exceptions," Java's type system forces a syntactic ritual (`try`/`catch`) that *looks* like handling but usually isn't:

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

### The Solution

**Operational failures are values.** They belong in normal control flow, not exception handling.

```java
Outcome<Response> result = boundary.call("UserApi.fetch", () -> httpClient.send(request));

return switch (result) {
    case Outcome.Ok(var response, var _) -> processResponse(response);
    case Outcome.Fail(var failure, var _) -> handleFailure(failure);
};
```

**Defects remain exceptions.** A `NullPointerException` should crash the operation and page an operator.

**Terminal errors are not handled.** An `OutOfMemoryError` means the JVM is compromised. Let the process die and let your infrastructure restart it.

This isn't a new idea—it's how Rust, Go, and functional languages handle errors. Outcome brings this proven idiom to Java with full type safety and pattern matching support.

---

## Core Concepts

### Outcome&lt;T&gt;

A sealed interface representing success or failure:

```java
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Fail {
    record Ok<T>(T value, Optional<String> correlationId) implements Outcome<T> {}
    record Fail<T>(Failure failure, Optional<String> correlationId) implements Outcome<T> {}
}
```

Use pattern matching for exhaustive handling:

```java
String message = switch (outcome) {
    case Outcome.Ok(var user, var _) -> "Hello, " + user.name();
    case Outcome.Fail(var failure, var _) -> "Error: " + failure.message();
};
```

Or use combinators for functional composition:

```java
Outcome<Order> order = fetchUser(userId)
    .flatMap(user -> fetchCart(user.cartId()))
    .flatMap(cart -> createOrder(cart));
```

### Creating Outcomes

```java
// Success
Outcome<String> success = Outcome.ok("result");

// Failure
Outcome<String> failure = Outcome.fail(Failure.transientFailure(
    FailureId.of("http", "timeout"),
    "Connection timed out",
    "HttpClient.send",
    exception
));

// With correlation ID (typically done by Boundary)
Outcome<String> correlated = Outcome.ok("result").correlationId("trace-123");
```

### Outcome Combinators

Transform and compose outcomes using functional combinators:

```java
// map — transform the success value
Outcome<Integer> length = outcome.map(String::length);

// flatMap — chain operations that return Outcome
Outcome<User> user = fetchUserId()
    .flatMap(id -> fetchUser(id));

// recover — provide fallback for failures
Outcome<Config> config = loadConfig()
    .recover(failure -> Outcome.ok(Config.defaults()));

// recoverWith — provide fallback based on failure
Outcome<Data> data = fetchFromPrimary()
    .recoverWith(failure -> {
        if (failure.type() == FailureType.TRANSIENT) {
            return fetchFromBackup();
        }
        return Outcome.fail(failure);
    });
```

Correlation IDs are preserved through transformations:

```java
Outcome<String> original = Outcome.<Integer>ok(42).correlationId("trace-1");
Outcome<String> mapped = original.map(n -> "Value: " + n);
// mapped.correlationId() returns Optional.of("trace-1")
```

### Failure

A structured failure with everything needed for reporting and policy decisions:

```java
Failure failure = Failure.builder(
    FailureId.of("http", "timeout"),
    "Connection timed out after 5000ms",
    FailureType.TRANSIENT,
    "PaymentGateway.process"
)
    .exception(cause)
    .correlationId("trace-123")
    .tags(Map.of("service", "payments", "region", "us-east-1"))
    .retryAfter(Duration.ofSeconds(5))
    .build();
```

**Failure components:**

| Component       | Description                                                 |
|-----------------|-------------------------------------------------------------|
| `FailureId`     | Namespaced identifier (`network:timeout`, `sql:connection`) |
| `FailureType`   | `TRANSIENT`, `PERMANENT`, or `DEFECT`                       |
| `message`       | Human-readable description                                  |
| `operation`     | The operation that failed                                   |
| `exception`     | The underlying throwable (optional)                         |
| `correlationId` | Trace correlation (optional)                                |
| `tags`          | Key-value metadata for observability                        |
| `retryAfter`    | Advisory delay before retry                                 |

**Failure types:**

| Type        | Meaning                                   | Retry? |
|-------------|-------------------------------------------|--------|
| `TRANSIENT` | Temporary condition, may succeed on retry | Yes    |
| `PERMANENT` | Persistent condition, retry won't help    | No     |
| `DEFECT`    | Bug in code or configuration              | No     |

**Factory methods:**

```java
// Transient failures (retryable)
Failure.transientFailure(id, message, operation, exception);
Failure.transientFailure(id, message, operation, exception, retryAfter);

// Permanent failures (not retryable)
Failure.permanentFailure(id, message, operation, exception);

// Defects (bugs)
Failure.defect(id, message, operation, exception);
```

---

## Boundary

### Purpose

Boundary is the single point where non-deterministic operations are acknowledged, observed, and converted into outcomes. It is:

- The **observation point** for fallible operations
- The **instrumentation point** for timing and success/failure tracking
- The **translation point** for exception-throwing code
- A **formal contract** that the application handles indeterminacy

### Basic Usage

```java
Boundary boundary = Boundary.withReporter(reporter);

Outcome<Response> result = boundary.call("HttpClient.send", () ->
    httpClient.send(request)  // throws IOException
);
```

What happens:
1. Boundary generates a correlation ID
2. Reports `onStart` event
3. Executes the work
4. If exception thrown: classifies, reports failure, returns `Outcome.Fail`
5. If success: wraps result in `Outcome.Ok`
6. Reports `onEnd` event
7. Returns outcome with correlation ID attached

### Creating a Boundary

```java
// Silent — no reporting (for testing)
Boundary boundary = Boundary.silent();

// With reporter — production use
Boundary boundary = Boundary.withReporter(reporter);

// Full control — custom classifier and reporter
Boundary boundary = Boundary.of(classifier, reporter);

// Custom correlation ID generation
Boundary boundary = new Boundary(
    classifier,
    reporter,
    () -> "custom-" + UUID.randomUUID()
);
```

### Correlation IDs

Every Boundary call generates a correlation ID that:
- Links `onStart` → retries → `onEnd` events
- Is attached to the returned Outcome
- Enables distributed tracing integration

```java
Outcome<User> result = boundary.call("UserService.fetch", () -> userService.fetch(id));

String correlationId = result.correlationId().orElse("none");
// e.g., "550e8400-e29b-41d4-a716-446655440000"
```

**Custom correlation ID generation:**

```java
// Use external trace ID
Boundary boundary = new Boundary(
    classifier,
    reporter,
    () -> MDC.get("traceId")  // From logging context
);

// Use request header
Boundary boundary = new Boundary(
    classifier,
    reporter,
    () -> request.getHeader("X-Correlation-ID")
);
```

---

## Operator Reporting

### OpReporter Interface

OpReporter receives events from Boundary and Retrier:

```java
public interface OpReporter {
    // Report a failure
    void report(Failure failure);

    // Retry events (default no-op)
    default void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay) {}
    default void reportRetryExhausted(Failure failure, int totalAttempts) {}
}
```

**Event sequence for a failed operation with retries:**
```
1. report(failure)  // First attempt fails
2. reportRetryAttempt(failure, 1, 100ms)
3. report(failure)  // Second attempt fails
4. reportRetryAttempt(failure, 2, 200ms)
5. // Third attempt succeeds - no failure reported
```

**Event sequence for exhausted retries:**
```
1. report(failure)  // First attempt fails
2. reportRetryAttempt(failure, 1, 100ms)
3. report(failure)  // Second attempt fails
4. reportRetryAttempt(failure, 2, 200ms)
5. report(failure)  // Third attempt fails
6. reportRetryExhausted(failure, 3)
```

### Built-in Reporters

**Log4jOpReporter** — SLF4J structured logging:

```java
OpReporter reporter = new Log4jOpReporter();
OpReporter reporter = new Log4jOpReporter("custom.logger.name");
```

Logs include:
- Failures with appropriate log levels (DEFECT→ERROR, PERMANENT→WARN, TRANSIENT→INFO)
- Retry attempts and exhaustion
- Correlation IDs and tags

**MetricsOpReporter** — JSON-lines metrics output:

```java
OpReporter reporter = new MetricsOpReporter("myapp");
```

Outputs structured JSON for each event:

```json
{"eventType":"failure","timestamp":"2024-01-20T10:30:00Z","trackingKey":"myapp.PaymentGateway.process","code":"http:timeout","type":"TRANSIENT","operation":"PaymentGateway.process","correlationId":"corr-123"}
{"eventType":"retry_attempt","timestamp":"2024-01-20T10:30:01Z","operation":"PaymentGateway.process","attemptNumber":1,"delayMs":100}
{"eventType":"retry_exhausted","timestamp":"2024-01-20T10:30:02Z","operation":"PaymentGateway.process","totalAttempts":3}
```

**CompositeOpReporter** — fan out to multiple reporters:

```java
OpReporter reporter = CompositeOpReporter.of(
    new Log4jOpReporter(),
    new MetricsOpReporter("myapp"),
    slackReporter
);
```

**NoOp reporter:**

```java
OpReporter reporter = OpReporter.noOp();
```

### Custom Reporters

Implement OpReporter for custom reporting needs:

```java
public class AlertingReporter implements OpReporter {

    @Override
    public void report(Failure failure) {
        if (failure.type() == FailureType.DEFECT) {
            pagerDuty.alert("DEFECT: " + failure.message());
        }
    }

    @Override
    public void onEnd(Outcome<?> outcome) {
        if (outcome.isFail()) {
            Outcome.Fail<?> fail = (Outcome.Fail<?>) outcome;
            metrics.increment("operation.failure",
                "code", fail.failure().id().toString());
        } else {
            metrics.increment("operation.success");
        }
    }

    @Override
    public void onRetryExhausted(Outcome.Fail<?> outcome, int totalAttempts) {
        slack.post("#ops", "Retry exhausted after " + totalAttempts +
            " attempts: " + outcome.failure().message());
    }
}
```

---

## Retry

### Retrier

Executes operations with retry logic based on policies:

```java
Retrier retrier = Retrier.builder()
    .policy(RetryPolicy.backoff(5, Duration.ofMillis(100), Duration.ofSeconds(10)))
    .reporter(reporter)
    .build();

Outcome<Response> result = retrier.execute(
    () -> boundary.call("UserApi.fetch", () -> userApi.fetch(userId))
);
```

### Retry Policies

**Fixed delay:**

```java
RetryPolicy.fixed(3, Duration.ofMillis(500));  // 3 attempts, 500ms between
```

**Exponential backoff:**

```java
RetryPolicy.backoff(5, Duration.ofMillis(100), Duration.ofSeconds(10));
// Attempts: 100ms, 200ms, 400ms, 800ms, capped at 10s
```

**Immediate retry (no delay):**

```java
RetryPolicy.immediate(3);  // 3 attempts, no delay
```

**No retry:**

```java
RetryPolicy.noRetry();  // Fail immediately
```

### Simple Retry

For quick prototyping without explicit Boundary setup:

```java
Outcome<String> result = Retrier.attempt(3, () -> {
    return httpClient.get(url);  // May throw
});
```

### Guided Retry

For scenarios where failures can inform subsequent attempts—such as LLM interactions where error context helps the model self-correct:

```java
Outcome<Order> result = Retrier.withGuidance(4)
    .attempt(() -> parse(llm.chat(request)))
    .deriveGuidance(failure -> "\n\nPrevious attempt failed: " + failure.message())
    .reattempt(guidance -> () -> parse(llm.chat(request + guidance)))
    .execute();
```

**With custom policy and reporter:**

```java
Outcome<Order> result = Retrier.withGuidance(4)
    .policy(RetryPolicy.fixed(4, Duration.ofSeconds(1)))
    .reporter(customReporter)
    .attempt(() -> parse(llm.chat(request)))
    .deriveGuidance(failure -> extractValidationErrors(failure))
    .reattempt(guidance -> () -> parse(llm.chat(request + guidance)))
    .execute();
```

The pattern:
1. **attempt** — the initial work to execute
2. **deriveGuidance** — converts a failure into guidance for the next attempt
3. **reattempt** — the work to execute on retries, given the derived guidance

### Time Budget

Limit total retry time:

```java
Retrier retrier = Retrier.builder()
    .policy(RetryPolicy.backoff(100, Duration.ofMillis(100), Duration.ofSeconds(30)))
    .budget(Duration.ofSeconds(60))  // Give up after 60 seconds total
    .build();
```

### Retry Hints

Failures can suggest retry delays (e.g., from HTTP 429/503 `Retry-After` headers):

```java
Failure failure = Failure.transientFailure(
    FailureId.of("http", "rate_limited"),
    "Rate limited",
    "Api.call",
    exception,
    Duration.ofSeconds(30)  // Retry after 30 seconds
);
```

Retry policies respect these hints, using the maximum of the calculated delay and the hint.

---

## Failure Classification

### FailureClassifier

Translates exceptions into Failures:

```java
public interface FailureClassifier {
    Failure classify(String operation, Throwable throwable);
}
```

### BoundaryFailureClassifier

The default classifier with sensible mappings:

| Exception Type           | Failure Type | Failure ID               |
|--------------------------|--------------|--------------------------|
| `SocketTimeoutException` | TRANSIENT    | `network:socket_timeout` |
| `ConnectException`       | TRANSIENT    | `network:connect`        |
| `HttpTimeoutException`   | TRANSIENT    | `network:http_timeout`   |
| `InterruptedIOException` | TRANSIENT    | `io:interrupted`         |
| `SQLException`           | TRANSIENT    | `sql:error`              |
| `IOException`            | TRANSIENT    | `io:error`               |
| `RuntimeException`       | DEFECT       | `defect:{className}`     |

### Custom Classification

```java
FailureClassifier classifier = (operation, throwable) -> {
    if (throwable instanceof RateLimitException rle) {
        return Failure.transientFailure(
            FailureId.of("api", "rate_limited"),
            "Rate limited: " + rle.getMessage(),
            operation,
            throwable,
            rle.getRetryAfter()
        );
    }
    // Fall back to default
    return new BoundaryFailureClassifier().classify(operation, throwable);
};

Boundary boundary = Boundary.of(classifier, reporter);
```

---

## Operational Exception Handler

### Purpose

Catches uncaught RuntimeExceptions at the top of the call stack, reports them as defects, and terminates the thread:

```java
OperationalExceptionHandler handler = new OperationalExceptionHandler(
    new DefaultDefectClassifier(),
    reporter
);

// Install as default for all threads
handler.installAsDefault();

// Or for specific thread
Thread.currentThread().setUncaughtExceptionHandler(handler);
```

### Defect Classification

Translates uncaught exceptions into defect-type Failures:

```java
public interface DefectClassifier {
    Failure classify(Thread thread, Throwable throwable);
}
```

The default classifier extracts operation context from the stack trace.

---

## Recommended Patterns

### Pattern 1: Simple Synchronous Call

For traditional thread-per-request applications:

```java
Outcome<User> result = boundary.call("UserService.fetch", () ->
    userRepository.findById(id)
);
```

### Pattern 2: Async with Virtual Threads

For concurrent workloads, use virtual threads:

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

Future<Outcome<User>> future = executor.submit(() ->
    boundary.call("UserService.fetch", () -> userRepository.findById(id))
);

// Do other work...

Outcome<User> result = future.get();
```

The Boundary crossing is synchronous (on the virtual thread). Async behavior is achieved at the caller level.

### Pattern 3: With Retry

For operations that may need retry:

```java
Outcome<Response> result = retrier.execute(() ->
    boundary.call("PaymentGateway.process", () -> gateway.process(payment))
);
```

Each retry attempt passes through Boundary. Boundary instruments each attempt; Retrier orchestrates the loop.

### Pattern 4: Service Layer Integration

```java
public class OrderService {
    private final Boundary boundary;
    private final PaymentGateway paymentGateway;
    private final InventoryService inventoryService;

    public Outcome<Order> createOrder(OrderRequest request) {
        return boundary.call("OrderService.validateRequest", () -> validate(request))
            .flatMap(validated ->
                boundary.call("InventoryService.reserve", () ->
                    inventoryService.reserve(validated.items())))
            .flatMap(reservation ->
                boundary.call("PaymentGateway.charge", () ->
                    paymentGateway.charge(request.payment())))
            .map(payment -> new Order(request, reservation, payment));
    }
}
```

### Pattern 5: REST Controller

```java
@RestController
public class UserController {
    private final Boundary boundary;
    private final UserService userService;

    @GetMapping("/users/{id}")
    public ResponseEntity<User> getUser(@PathVariable String id) {
        Outcome<User> result = boundary.call("UserController.getUser", () ->
            userService.findById(id)
        );

        return switch (result) {
            case Outcome.Ok(var user, var _) -> ResponseEntity.ok(user);
            case Outcome.Fail(var failure, var _) -> switch (failure.type()) {
                case TRANSIENT -> ResponseEntity.status(503).build();
                case PERMANENT -> ResponseEntity.status(404).build();
                case DEFECT -> ResponseEntity.status(500).build();
            };
        };
    }
}
```

---

## Anti-Patterns

### Anti-Pattern 1: Blocking Event Loop Threads

```java
// In a Vert.x or WebFlux handler — DON'T DO THIS
public Mono<Response> handle(Request request) {
    Outcome<Data> result = boundary.call("Service.fetch", () ->
        asyncService.fetch(request).block()  // Blocks event loop!
    );
    return Mono.just(toResponse(result));
}
```

**Solution:** Migrate to virtual threads, or use worker thread pools.

### Anti-Pattern 2: Fire-and-Forget Through Boundary

```java
// DON'T DO THIS
executor.submit(() ->
    boundary.call("Notification.send", () -> notificationService.send(msg))
);
// Caller doesn't wait for result
```

**Why it's wrong:** If you don't observe the outcome, you can't handle failures.

**Solution:** Wait for the result, or reconsider whether Boundary is appropriate.

### Anti-Pattern 3: Unnecessary Nested Boundaries

```java
// DON'T DO THIS
boundary.call("Outer", () ->
    boundary.call("Inner", () -> service.call())
);
```

**Why it's wrong:** Creates duplicate instrumentation and misleading metrics.

**Solution:** One Boundary per logical operation.

### Anti-Pattern 4: Catching Outcome Instead of Pattern Matching

```java
// DON'T DO THIS
try {
    String value = outcome.getOrThrow();
} catch (FailureException e) {
    // Handle failure
}
```

**Solution:** Use pattern matching:

```java
return switch (outcome) {
    case Outcome.Ok(var value, var _) -> process(value);
    case Outcome.Fail(var failure, var _) -> handleFailure(failure);
};
```

---

## Architecture Overview

```
                         ┌───────────────────────────────────┐
                         │  Error (OOME, StackOverflow)      │
                         │  - NOT CAUGHT                     │
                         │  - JVM terminates                 │
                         │  - Infrastructure restarts        │
                         └───────────────────────────────────┘
                                          ↑
                                    propagates
                                          │
┌─────────────────────────────────────────────────────────────────┐
│  OperationalExceptionHandler (top of stack)                     │
│  - Catches uncaught RuntimeExceptions (defects)                 │
│  - Reports to OpReporter                                        │
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
│  - Enriches failures with correlation ID and tags               │
│  - Checked exception → classify → report → Outcome.Fail         │
│  - RuntimeException → rethrow (defect)                          │
└─────────────────────────────────────────────────────────────────┘
                              ↑
                     third-party API throws
                              │
┌─────────────────────────────────────────────────────────────────┐
│  Third-Party Code (JDBC, HTTP clients, SDKs)                    │
│  - Throws checked exceptions                                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Package Structure

```
org.javai.outcome
├── Outcome, Failure, FailureType, FailureId
│
├── boundary/
│   ├── Boundary
│   ├── ThrowingSupplier
│   ├── FailureClassifier, BoundaryFailureClassifier
│
├── retry/
│   ├── Retrier, RetryPolicy
│   ├── RetryDecision, RetryContext
│
└── ops/
    ├── OpReporter, CompositeOpReporter
    ├── DefectClassifier, DefaultDefectClassifier
    ├── OperationalExceptionHandler
    ├── log4j/
    │   └── Log4jOpReporter
    └── metrics/
        └── MetricsOpReporter
```

---

## Requirements

- Java 21+

### Installation

**Gradle (Kotlin DSL):**

```kotlin
implementation("org.javai:outcome:0.1.0")
```

**Gradle (Groovy DSL):**

```groovy
implementation 'org.javai:outcome:0.1.0'
```

**Maven:**

```xml
<dependency>
    <groupId>org.javai</groupId>
    <artifactId>outcome</artifactId>
    <version>0.1.0</version>
</dependency>
```

---

## Getting Started

```java
// 1. Set up infrastructure
OpReporter reporter = CompositeOpReporter.of(
    new Log4jOpReporter(),
    new MetricsOpReporter("myapp")
);

Boundary boundary = Boundary.withReporter(reporter);

Retrier retrier = Retrier.builder()
    .policy(RetryPolicy.backoff(3, Duration.ofMillis(100), Duration.ofSeconds(5)))
    .reporter(reporter)
    .build();

// 2. Install uncaught exception handler
new OperationalExceptionHandler(new DefaultDefectClassifier(), reporter)
    .installAsDefault();

// 3. Use in your code
Outcome<User> result = boundary.call("UserService.findById", () ->
    userRepository.findById(id)
);

return switch (result) {
    case Outcome.Ok(var user, var _) -> ResponseEntity.ok(user);
    case Outcome.Fail(var f, var _) -> ResponseEntity.status(503).body(f.message());
};
```

---

## Philosophy

### The Boundary Principle

Software systems are composed of two fundamentally different domains:

**Deterministic code** — your application logic. Given the same inputs, it produces the same outputs.

**Fallible operations** — databases, APIs, networks. They may succeed, fail, timeout, or not respond.

Outcome's central insight is that **the boundary between these domains deserves first-class treatment**. The `Boundary` component marks these crossing points explicitly. Uncertainty is acknowledged, observed, and resolved into a well-defined `Outcome`.

### The Outcome Principle

**Recoverable failures flow as values. Defects crash and page. Terminal errors terminate.**

| Category        | Examples              | Handling        | Recovery                |
|-----------------|-----------------------|-----------------|-------------------------|
| **Recoverable** | Timeouts, rate limits | `Outcome.Fail`  | Retry, fallback         |
| **Defect**      | NullPointerException  | Propagate, page | Human fixes code        |
| **Fatal**       | OutOfMemoryError      | Don't catch     | Infrastructure restarts |

A socket timeout isn't exceptional — it's Tuesday. The network is unreliable by nature. Treating timeouts as exceptions is like treating rain as an exception to weather.

### What We Gain

By representing expected failures as data:

- **Composition** — failures flow through `flatMap` and `recover`
- **Type safety** — the compiler ensures handling
- **Consistency** — one failure model across the codebase
- **Observability** — structured reporting with tags for domain-specific metadata
- **Extensibility** — tags allow downstream consumers to interpret metadata as needed
