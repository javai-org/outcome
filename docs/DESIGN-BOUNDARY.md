# Boundary Design Document

This document captures the design philosophy and decisions for the `Boundary` component of the Outcome framework.

---

## Conceptual Foundation

### Boundary as Indeterminacy Declaration

Boundary is not merely an adapter for converting exceptions to `Outcome` values. It is a **formal declaration that indeterminacy begins here**.

When code crosses a Boundary, the developer acknowledges:
- The invoked operation may not behave predictably
- The outcome is one of two well-defined states: `Ok` or `Fail`
- The operation warrants observation and instrumentation

This framing elevates Boundary from a mechanical translation layer to a semantic marker in the architecture. Every Boundary crossing says: *"The code I'm calling may fail for reasons outside my control, and I'm prepared for either outcome."*

### What Boundary Is

- The **observation point** for non-deterministic operations
- The **instrumentation point** for timing, success/failure rates, and retry tracking
- The **translation point** for code that throws exceptions (when applicable)
- A **formal contract** that the application acknowledges and handles indeterminacy

### What Boundary Is Not

- Not exclusively an exception-to-Outcome adapter
- Not a retry mechanism (that's `Retrier`)
- Not a policy decision point (that's `RetryPolicy`)
- Not specific to any particular type of external service

---

## Design Decisions

### 1. Equal Treatment of All Indeterminate Operations

**Decision:** Boundary handles both exception-throwing code and code that already returns `Outcome<T>`.

**Justification:**

The nature of indeterminacy is independent of how the invoked code expresses it. A network call that throws `IOException` and an Outcome-native service that returns `Outcome.Fail` are conceptually equivalent — both represent non-deterministic operations that may fail.

Boundary's role is instrumentation and observation, not translation. Exception translation is incidental — it's what happens when the underlying code uses exceptions. But it's not Boundary's *purpose*.

**API Implication:**

```java
// Handles exception-throwing code
<T> Outcome<T> call(String operation, ThrowingSupplier<T, Exception> work);

// Handles Outcome-returning code
<T> Outcome<T> call(String operation, Supplier<Outcome<T>> work);
```

Both methods:
1. Generate correlation ID
2. Emit start event (operation, correlation ID, timestamp, covariates)
3. Invoke work
4. Emit end event (operation, correlation ID, timestamp, outcome, covariates)
5. Return the outcome

The only difference is whether translation is needed. That's an implementation detail.

---

### 2. Lifecycle Event Reporting

**Decision:** Boundary reports **two distinct events** per invocation — a start event and an end event — not a single event with embedded duration.

**Justification:**

A Boundary operation involves two crossings:

1. **Outbound crossing** — the moment control leaves deterministic code and enters the fallible operation
2. **Inbound crossing** — the moment control returns with an outcome

Each crossing is an event. Each event has a timestamp. Duration is *derived* by correlating the two events, not embedded in either. This is conceptually cleaner — each event represents a point in time, not a span.

Failure-only reporting is insufficient for:
- **Latency monitoring:** Signal needs duration of successful operations, not just failures
- **Success rate calculation:** Computing rates requires knowing both successes and failures
- **SPC analysis:** Control charts need the full picture of process behavior

By reporting lifecycle events, Boundary becomes the single instrumentation point for all non-deterministic operations. Consumers (like Signal) receive complete data and decide what to analyze.

**Event Data:**

Start event:
- Operation name
- Correlation ID (to link with end event)
- Timestamp
- Covariates

End event:
- Operation name
- Correlation ID (matches start event)
- Timestamp
- Outcome (Ok or Fail)
- Failure details (if Fail)
- Covariates

Duration is computed by the consumer: `end.timestamp - start.timestamp`

**Consumer Filtering:**

Event volume is a consumer concern, not a Boundary concern. Boundary emits everything; the `OpReporter` implementation decides what to record:
- A no-op reporter ignores successes
- A Signal reporter wants everything
- A sampling reporter records 1-in-N

---

### 3. Synchronous Boundary Crossings

**Decision:** Boundary crossings are always synchronous. Asynchronous behavior is the caller's concern.

**Justification:**

A Boundary crossing has a clear start and end. This is fundamental to:
- Capturing accurate timing
- Knowing when an operation completes
- Reporting a well-defined outcome

Asynchronous fire-and-forget patterns obscure this. If you don't wait for the result, you don't know the outcome, and you can't report it meaningfully.

**The Synchronous Principle:**

```
Boundary.call() blocks until the operation completes and returns an Outcome.
```

This is non-negotiable. The operation may internally be async, but Boundary waits for resolution.

---

### 4. Virtual Threads for Async Workloads

**Decision:** For async workloads, use virtual threads. Boundary does not accommodate reactive or callback-based execution models.

**Justification:**

**Virtual threads change everything.**

Prior to Java 21, blocking a thread was expensive. A thread-per-request model with blocking I/O couldn't scale. Reactive frameworks (WebFlux, Vert.x, RxJava) emerged to avoid blocking, using small thread pools and non-blocking I/O.

Virtual threads eliminate this trade-off. A virtual thread can block without consuming an OS thread. Millions of virtual threads can be blocked simultaneously without resource exhaustion.

**The modern approach:**

```java
// Async from caller's perspective, sync at the Boundary
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

Future<Outcome<Response>> future = executor.submit(() ->
    boundary.call("PaymentGateway.process", () -> gateway.process(payment))
);

// Boundary blocks on the virtual thread — cheap and safe
```

**Reactive frameworks are dated.**

Reactive programming was a workaround for platform thread limitations. With virtual threads:
- The complexity of reactive chains is unnecessary
- The cognitive overhead of non-blocking thinking is unnecessary
- Straightforward synchronous code performs equivalently

Outcome does not cater to reactive frameworks. Developers using WebFlux or Vert.x should:
1. Migrate to virtual threads (preferred)
2. Use worker thread pools for Boundary crossings (workaround)
3. Accept that they're operating outside Outcome's design center

---

### 5. Developer Responsibility

**Decision:** Developers are responsible for understanding their execution context. Outcome provides tools, not guardrails for every scenario.

**Justification:**

Outcome targets professional developers who:
- Understand threading and concurrency
- Can reason about blocking vs non-blocking execution
- Make informed architectural decisions

Trying to protect developers from every possible misuse:
- Adds complexity to the framework
- Reduces flexibility
- Often fails anyway (developers find ways around guardrails)

**What Outcome provides:**
- Clear documentation of the synchronous model
- Virtual thread compatibility
- Correct behavior when used as designed

**What Outcome does not provide:**
- Automatic detection of event loop blocking
- Reactive framework adapters
- Callback-based APIs

---

### 6. Covariates as First-Class Types

**Decision:** Covariates are first-class types, not string maps. Outcome predefines common covariates and allows custom covariates for domain-specific needs.

**Justification:**

Covariates define **subgroup membership criteria** for statistical analysis. They are not arbitrary metadata — they have analytical meaning. A covariate like `DaysOfWeek([MON, TUE, WED, THU, FRI])` declares: "Events occurring on these days belong to this subgroup."

This is fundamentally different from tags (general metadata for observability). Mixing them in a `Map<String, String>` obscures the intent and creates problems:
- Consumers can't distinguish analytical dimensions from incidental metadata
- String keys invite inconsistency (`"region"` vs `"Region"` vs `"REGION"`)
- High-cardinality tags could poison statistical analysis

**Predefined Covariates:**

```java
public sealed interface Covariate permits DaysOfWeek, TimeOfDay, Region, CustomCovariate {
    String name();
}

public record DaysOfWeek(Set<DayOfWeek> days) implements Covariate {

    public static DaysOfWeek of(DayOfWeek... days) {
        return new DaysOfWeek(Set.of(days));
    }

    public static DaysOfWeek weekdays() {
        return of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY);
    }

    public static DaysOfWeek weekends() {
        return of(SATURDAY, SUNDAY);
    }

    @Override
    public String name() { return "days_of_week"; }
}

public record TimeOfDay(int fromHour, int toHour) implements Covariate {

    public static TimeOfDay businessHours() {
        return new TimeOfDay(9, 17);
    }

    public static TimeOfDay offHours() {
        return new TimeOfDay(17, 9);
    }

    @Override
    public String name() { return "time_of_day"; }
}

public record Region(String value) implements Covariate {
    @Override
    public String name() { return "region"; }
}

public record CustomCovariate(String name, String value) implements Covariate {}
```

**Covariate Semantics:**

Covariates define which events belong to a subgroup:

- `DaysOfWeek.weekdays()` — events on Mon-Fri belong to this subgroup
- `DaysOfWeek.of(MONDAY)` — only Monday events (weekly sampling)
- `TimeOfDay.businessHours()` — events during 9-17 hours
- `Region("us-east-1")` — events from this region

The developer declares: "For this operation, analyze events matching these criteria as a coherent population."

**Separation of Concerns:**

Outcome defines covariate *data*. It does not implement matching logic.

Signal (or any consumer) interprets covariates and implements `matches(Instant, Covariate)` to route events to appropriate subgroups. This keeps Outcome focused on data representation while allowing consumers to implement their own routing logic.

**Covariates vs Tags:**

| Aspect | Covariates | Tags |
|--------|------------|------|
| **Purpose** | Define statistical subgroups | General observability metadata |
| **Type safety** | First-class types | String maps |
| **Cardinality** | Must be bounded | Can be high-cardinality |
| **Interpretation** | Signal routes by these | Passed through to reporters |

---

## Recommended Patterns

### Pattern 1: Simple Synchronous Call

For traditional thread-per-request applications:

```java
Outcome<User> result = boundary.call("UserService.fetch", () ->
    userRepository.findById(id)
);
```

The calling thread blocks until completion. This is the simplest and most common pattern.

### Pattern 2: Async with Virtual Threads

For concurrent or async workloads:

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

### Pattern 4: Wrapping Outcome-Native Code

For code that already returns `Outcome`:

```java
Outcome<Order> result = boundary.call("OrderService.create", () ->
    orderService.create(request)  // returns Outcome<Order>
);
```

No exception translation needed. Boundary provides instrumentation only.

---

## Anti-Patterns

### Anti-Pattern 1: Blocking Event Loop Threads

```java
// In a Vert.x or WebFlux handler — DON'T DO THIS
public Mono<Response> handle(Request request) {
    // This blocks the event loop — DISASTER
    Outcome<Data> result = boundary.call("Service.fetch", () ->
        asyncService.fetch(request).block()
    );
    return Mono.just(toResponse(result));
}
```

**Why it's wrong:** Event loop threads must never block. This stalls the entire event loop.

**Solution:** Migrate away from reactive frameworks, or use worker thread pools.

### Anti-Pattern 2: Fire-and-Forget Through Boundary

```java
// DON'T DO THIS
executor.submit(() ->
    boundary.call("Notification.send", () -> notificationService.send(msg))
);
// Caller doesn't wait for result
```

**Why it's wrong:** If you don't observe the outcome, you can't handle failures. The Boundary crossing is instrumented, but the result is discarded.

**Solution:** If you truly don't care about the result, reconsider whether Boundary is appropriate. If you do care, wait for the result.

### Anti-Pattern 3: Nested Boundaries

```java
// Unnecessary nesting
boundary.call("Outer", () ->
    boundary.call("Inner", () -> service.call())
);
```

**Why it's wrong:** Each Boundary crossing is instrumented. Nesting creates duplicate events and misleading metrics.

**Solution:** One Boundary per logical operation.

---

## Relationship to Other Components

### Boundary and Retrier

- **Boundary:** Instruments individual invocations
- **Retrier:** Orchestrates retry loops over Boundary-wrapped operations

They are complementary. Retrier calls Boundary repeatedly; each call is instrumented independently.

### Boundary and OpReporter

- **Boundary:** Emits lifecycle events
- **OpReporter:** Receives and processes events

OpReporter implementations decide what to do with events — log them, send to metrics systems, feed to Signal, or ignore.

### Boundary and OpReporter Implementations

Boundary emits events to OpReporter. What happens next depends on the implementation:

| Implementation | Purpose |
|----------------|---------|
| `Slf4jOpReporter` | Logs failures via SLF4J |
| `MetricsOpReporter` | Emits metrics to observability systems |
| `Signal` | Performs SPC analysis, emits statistically qualified alerts |
| `CompositeOpReporter` | Fans out to multiple reporters |
| Custom implementations | Whatever the application needs |

Signal is one of many possible OpReporter implementations. Boundary has no direct knowledge of Signal — it simply emits events to whatever OpReporter is configured.

---

## Summary

| Aspect | Decision |
|--------|----------|
| **Conceptual role** | Indeterminacy declaration point, not just exception adapter |
| **Code support** | Exception-throwing and Outcome-returning equally |
| **Event reporting** | Two events per crossing (start and end); duration computed by consumer |
| **Execution model** | Synchronous crossings only |
| **Async approach** | Virtual threads; reactive frameworks not accommodated |
| **Covariates** | First-class types (DaysOfWeek, TimeOfDay, Region, Custom); not string maps |
| **Developer model** | Responsible professionals; no excessive guardrails |

---

## References

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Outcome Framework README](../README.md)
- [Signal SPC Design Questionnaire](../../signal/docs/spc-design-questionnaire.md)
