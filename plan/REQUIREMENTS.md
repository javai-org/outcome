# Requirements Specification: Standardized Outcomes, Failure Model, and Integration with Checked-Exception APIs

## 1. Purpose and Scope

This specification defines a unified framework approach for:

1. representing expected operational failure as first-class values using a standardized `Outcome<T>` type,
2. representing failure uniformly using a canonical `Failure` model suitable for operator reporting and automated policy decisions, and
3. integrating with third-party Java APIs that still employ checked exceptions, without allowing checked exceptions to leak into application code.

The specification applies to:

- application and framework code that performs network or I/O operations,
- boundary adapters integrating with third-party libraries (JDK I/O, JDBC, HTTP clients, SDKs),
- retry/fallback/degradation policies, and
- operator notification and observability emission associated with failures.

This specification does not standardize alerting infrastructure. It standardizes application-level intent and semantics, enabling consistent downstream handling.

---

## 2. Problem Statement

Operational failure is expected in production systems. However, Java's checked exceptions:

- force control-flow fractures (try/catch) that distort code structure,
- encourage syntactic compliance rather than policy-driven handling, and
- do not provide standardized semantics for reporting, aggregation, or retry guidance.

In the absence of a standardized approach, codebases typically exhibit:

- bespoke "error subtype" proliferation (every sealed return type invents its own failure variants),
- inconsistent retry logic and inconsistent observability,
- ad-hoc logging as a proxy for operator notification,
- inconsistent classification of identical technical failures (e.g., `IOException` interpreted differently across call sites).

This specification establishes a disciplined approach whereby:

- application code does not throw checked exceptions,
- operational failures are represented as standardized values,
- third-party checked exceptions are caught only at framework boundaries and translated into standardized failures, and
- reporting and retry behavior are consistently policy-driven.

---

## 3. Design Principles

**P1. Operational failures are values**
Expected failures (timeouts, 503s, transient I/O) must be represented in normal control flow, not as checked exceptions.

**P2. Checked exceptions are quarantined**
Checked exceptions thrown by third-party APIs are handled only at designated boundary adapters and are immediately translated into `Outcome.Fail`.

**P3. Failures are standardized**
All failures are represented by a single canonical `Failure` model. No domain-specific failure subtypes are permitted.

**P4. Policies own behavior and reporting**
Retry/fallback/degradation and operator reporting are part of policy execution, not ad hoc in catch blocks.

**P5. No checked exceptions from application code**
Application code must not declare or propagate checked exceptions. Unchecked exceptions remain permitted and required for defect/misconfiguration.

---

## 4. Core Abstractions

### 4.1 Outcome Type

All operations that may encounter operational failure shall return:

```java
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Fail {

    record Ok<T>(T value) implements Outcome<T> {}

    record Fail<T>(Failure failure) implements Outcome<T> {}
}
```

Constraints:

- Exactly two variants: `Ok` and `Fail`.
- `Fail` must contain only the standardized `Failure`.
- No domain-specific error subtypes may be introduced.

---

### 4.2 Failure Model

All failures shall be represented using a single canonical model suitable for reporting and aggregation.

```java
public record Failure(
    FailureCode code,
    FailureCategory category,
    FailureStability stability,
    NotificationIntent notify,
    String operation,
    String message,
    Map<String, String> tags,
    Instant occurredAt,
    String correlationId,
    Cause cause,
    RetryHint retryHint // nullable
) {}
```

Enumerations:

```java
public enum FailureCategory { RECOVERABLE, DEFECT, TERMINAL }

public enum FailureStability { TRANSIENT, PERMANENT, UNKNOWN }

public enum NotificationIntent { NONE, OBSERVE, ALERT, PAGE }

public enum Retryability { NONE, MAYBE, YES }
```

Structured identifiers and causes:

```java
public record FailureCode(String namespace, String name) {
    public static FailureCode of(String namespace, String name) {
        return new FailureCode(namespace, name);
    }
}

public record Cause(String type, String fingerprint, String detail) {}

public record RetryHint(
    Retryability retryability,
    Duration minDelay,    // nullable
    Instant notBefore,    // nullable
    String reasonCode     // stable token
) {}
```

Constraints:

- `FailureCode` must be stable and namespaced.
- Tags must be bounded and must not contain secrets or high-cardinality values.
- `RetryHint` is advisory metadata; it must not be treated as a command.

---

## 5. Handling Third-Party APIs with Checked Exceptions

### 5.1 Boundary Adapter

The framework shall provide a boundary adapter responsible for:

- executing code that may throw checked exceptions,
- catching checked exceptions,
- classifying them into `Failure`,
- reporting them via `OpReporter`,
- returning `Outcome<T>`.

Functional interface:

```java
@FunctionalInterface
public interface ThrowingSupplier<T, E extends Exception> {
    T get() throws E;
}
```

Boundary API:

```java
public final class Boundary {
    private final FailureClassifier classifier;
    private final OpReporter reporter;

    public Boundary(FailureClassifier classifier, OpReporter reporter) {
        this.classifier = classifier;
        this.reporter = reporter;
    }

    public <T> Outcome<T> call(String operation, ThrowingSupplier<T, ? extends Exception> work) {
        try {
            return new Outcome.Ok<>(work.get());
        } catch (Exception e) {
            Failure f = classifier.classify(operation, e);
            reporter.report(f);
            return new Outcome.Fail<>(f);
        }
    }
}
```

Constraints:

- Checked exceptions shall not be allowed to propagate beyond `Boundary`.
- Catch blocks outside boundary modules for checked exceptions are prohibited except where explicitly approved.

---

### 5.2 Failure Classification

The framework shall provide centralized classification:

```java
public interface FailureClassifier {
    Failure classify(String operation, Throwable t);
}
```

Constraints:

- Classification must be deterministic.
- Classification must set category, stability, and `retryHint` consistently.
- Classification must use operation context to distinguish semantically different meanings of the same exception type (e.g., `IOException`).

---

## 6. Policy-Driven Retry Over Outcomes

The framework shall provide a retry executor that operates on `Outcome<T>` and does not require the caller to write try/catch.

```java
public interface RetryPolicy {
    String id();
    RetryDecision decide(RetryContext context, Failure failure);
}

public sealed interface RetryDecision permits RetryDecision.Retry, RetryDecision.GiveUp {
    record Retry(Duration delay) implements RetryDecision {}
    record GiveUp() implements RetryDecision {}
}

public record RetryContext(int attemptNumber, Instant startedAt, Duration elapsed, Duration remainingBudget) {}

public final class Retrier {
    public <T> Outcome<T> execute(String operation, RetryPolicy policy, java.util.function.Supplier<Outcome<T>> attempt) { ... }
}
```

Constraints:

- `Retrier` must never throw checked exceptions.
- Retry must respect budgets and cancellation.
- Reporting must be integrated: retry attempts and exhaustion must be reported with policy ID and attempt metadata.

---

## 7. Call-Site Scenarios (Normative Examples)

### 7.1 Scenario A: Pure Framework Code Returning Outcome

A method implemented within the framework/application returns `Outcome<T>` directly.

```java
Outcome<Contact> getContact(String id) {
    // Internal code; no checked exceptions are thrown.
    if (id == null || id.isBlank()) {
        return new Outcome.Fail<>(Failures.invalidInput("Contacts.getContact", "id must not be blank"));
    }
    return repository.findContact(id); // returns Outcome<Contact>
}
```

Handling at call site (normal control flow):

```java
Outcome<Contact> out = service.getContact(id);

return switch (out) {
    case Outcome.Ok<Contact> ok   -> ok.value();
    case Outcome.Fail<Contact> f  -> throw new UpstreamUnavailableException(f.failure());
};
```

---

### 7.2 Scenario B: Calling a Third-Party API That Throws Checked Exceptions (No Retry)

```java
Outcome<Response> out =
    boundary.call("HttpClient.send", () -> client.send(request));
```

No checked exceptions escape. The caller remains in outcome-space.

---

### 7.3 Scenario C: Calling a Third-Party API with Retry (Recommended Pattern)

Retry + checked exceptions are handled without direct try/catch at the call site:

```java
Outcome<Response> out =
    retrier.execute(
        "HubSpotClient.fetchContact",
        retryPolicies.idempotentNetworkCall(),
        () -> boundary.call("HubSpotClient.fetchContact", () -> client.call(request))
    );
```

Here:

- third-party `client.call` may throw `IOException`,
- `Boundary` catches it and produces `Outcome.Fail(Failure)`,
- `Retrier` consumes `Outcome` and applies policy and reporting.

---

### 7.4 Scenario D: Convenience Overload (Retry Executes Throwing Supplier)

The framework may provide an overload to reduce boilerplate:

```java
public <T> Outcome<T> execute(
    String operation,
    RetryPolicy policy,
    ThrowingSupplier<T, ? extends Exception> work
) {
    return execute(operation, policy, () -> boundary.call(operation, work));
}
```

Call site:

```java
Outcome<Response> out =
    retrier.execute(
        "HubSpotClient.fetchContact",
        retryPolicies.idempotentNetworkCall(),
        () -> client.call(request) // may throw IOException
    );
```

---

### 7.5 Scenario E: Mixed Composition (Outcome-Based Method Calling Checked-Exception API)

An outcome-returning method can safely integrate a checked-exception API:

```java
Outcome<Order> fetchOrder(String orderId) {
    return boundary.call("OrdersApi.fetchOrder", () -> ordersSdk.fetch(orderId));
}
```

And a higher layer can apply policy:

```java
Outcome<Order> out =
    retrier.execute("OrdersApi.fetchOrder", retryPolicies.idempotentNetworkCall(),
        () -> service.fetchOrder(orderId));
```

---

## 8. Explicit Prohibitions

The following are prohibited within application code:

- Declaring or propagating checked exceptions from framework-managed methods.
- Introducing custom failure subtypes in outcome types.
- Catching checked exceptions outside boundary adapters (except approved integration modules).
- Logging or printing as a substitute for reporting via standardized failure/reporting mechanisms.
- Implementing bespoke retry loops.

---

## 9. Conformance and Tooling Expectations

A conforming implementation shall provide:

- an `Outcome<T>` implementation with `Ok` and `Fail`,
- a canonical `Failure` model with stable `FailureCode` and reporting metadata,
- a boundary adapter that converts checked exceptions into `Outcome.Fail`,
- a failure classifier with deterministic mappings,
- a retry executor operating entirely over outcomes,
- standardized reporting hooks (`OpReporter`) invoked for:
  - each `Outcome.Fail`,
  - each retry attempt,
  - retry exhaustion.

Static analysis should be enabled to flag:

- checked exceptions escaping boundary adapters,
- catch blocks for checked exceptions outside approved modules,
- non-standard outcome variants.

---

## 10. Summary

This specification consolidates two realities:

1. Production systems must treat operational failure as a first-class concern and represent it explicitly as data in normal control flow.
2. Java's ecosystem still contains third-party APIs that throw checked exceptions, which must be integrated without contaminating the application with unchecked try/catch sprawl.

By standardizing `Outcome<T>`, centralizing failure modeling in `Failure`, quarantining checked exceptions at `Boundary` adapters, and applying retry/reporting policies in outcome-space, the framework enables disciplined, observable, and safe handling of operational failure across all call-site scenarios.