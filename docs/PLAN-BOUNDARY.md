# Boundary Enhancement Implementation Plan

This document itemizes the changes required to implement the enhanced Boundary design as specified in [DESIGN-BOUNDARY.md](DESIGN-BOUNDARY.md).

---

## Current State Summary

### Outcome
- `Ok<T>(T value)` — no correlation ID
- `Fail<T>(Failure failure)` — correlation ID only in Failure, not Outcome itself
- No `correlationId()` method

### Failure
- Already has `correlationId` field
- Has `withContext(correlationId, tags)` method

### OpReporter
```java
void report(Failure failure);
void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay);
void reportRetryExhausted(Failure failure, int totalAttempts);
```
- Failure and retry events
- Tags for observability metadata

### Boundary
- `call(operation, work)` — returns `Outcome<T>`
- `call(operation, tags, work)` — with observability tags
- Enriches failures with correlation ID and tags
- Reports failures via `reporter.report(failure)`

---

## Target State Summary

### Outcome
- `Ok<T>(T value)` — success value
- `Fail<T>(Failure failure)` — failure with optional correlation ID in Failure
- Correlation ID flows through Failure for tracing

### OpReporter
```java
void report(Failure failure);
void reportRetryAttempt(Failure failure, int attemptNumber, Duration delay);
void reportRetryExhausted(Failure failure, int totalAttempts);
```

### Boundary
- `call(operation, work)` — executes work and returns Outcome
- `call(operation, tags, work)` — with observability tags
- Enriches failures with correlation ID if configured
- Tags provide extensibility for domain-specific metadata

---

## Implementation Checklist

### Phase 1: Outcome Enhancement ✓

- [x] **2.1 Add correlationId to Outcome.Ok**
  - Modified `Ok<T>` record to include `Optional<String> correlationId`
  - Auto-generated `correlationId()` method returns `Optional<String>`
  - Convenience constructor `Ok(T value)` creates with empty Optional

- [x] **2.2 Add correlationId to Outcome.Fail**
  - Modified `Fail<T>` record to include `Optional<String> correlationId`
  - Auto-generated `correlationId()` method returns `Optional<String>`
  - Convenience constructor `Fail(Failure)` creates with empty Optional

- [x] **2.3 Add correlationId method to Outcome**
  - `Outcome<T> correlationId(String correlationId)`
  - Returns new instance with correlation ID set

- [x] **2.4 Update Outcome static factories**
  - `ok(T value)` — creates Ok with empty correlationId (uses post-processing approach)
  - `fail(Failure failure)` — creates Fail with empty correlationId
  - Note: Overloaded factories with correlationId parameter not needed; use `correlationId(String)` method

- [x] **2.5 Update Outcome.map/flatMap to preserve correlationId**
  - map preserves correlation ID
  - flatMap preserves correlation ID if result has none; result's ID takes precedence if set
  - recover/recoverWith also preserve correlation ID

- [x] **2.6 Add tests for Outcome correlation ID**
  - File: `src/test/java/org/javai/outcome/OutcomeCorrelationTest.java`

### Phase 2: OpReporter ✓

- [x] **2.1 OpReporter interface**
  - `void report(Failure failure)` — report a failure
  - `void reportRetryAttempt(Failure, int, Duration)` — retry attempt with delay
  - `void reportRetryExhausted(Failure, int)` — retries exhausted
  - Default no-op implementations for retry methods

### Phase 3: Boundary ✓

- [x] **3.1 Boundary class**
  - `call(operation, work)` — executes work, returns Outcome
  - `call(operation, tags, work)` — with observability tags
  - Correlation ID supplier (configurable)
  - Enriches failures with correlation ID and tags

- [x] **3.2 Add tests for Boundary**
  - File: `src/test/java/org/javai/outcome/boundary/BoundaryTest.java`

### Phase 4: OpReporter Implementations ✓

- [x] **4.1 Log4jOpReporter**
  - Logs failures with structured context
  - Logs retry attempts and exhaustion

- [x] **4.2 MetricsOpReporter**
  - Outputs JSON for metrics integration
  - Includes correlation ID and tags

- [x] **4.3 CompositeOpReporter**
  - Fans out to multiple reporters

- [x] **4.4 Tests for all reporters**

### Phase 5: Retrier Integration ✓

- [x] **5.1 Retrier uses OpReporter methods**
  - `reportRetryAttempt(Failure, int, Duration)`
  - `reportRetryExhausted(Failure, int)`

- [x] **5.2 Correlation ID flows through retries**
  - Failure carries correlation ID through retry sequence

- [x] **5.3 Retrier tests**
  - File: `src/test/java/org/javai/outcome/retry/RetrierTest.java`

### Phase 6: Documentation

- [x] **6.1 Update README.md**
  - Document Boundary and Retrier usage
  - Document OpReporter implementations

- [x] **6.2 Update Javadoc**
  - All classes and methods documented

- [ ] **6.3 Create user guide**
  - Comprehensive guide for Outcome framework

---

## Design Notes

- Tags provide extensibility — domain-specific metadata flows through the system
- Correlation ID is optional and configurable via supplier
- OpReporter default implementations are no-op for backward compatibility
- Retrier reports retry events to OpReporter for observability
