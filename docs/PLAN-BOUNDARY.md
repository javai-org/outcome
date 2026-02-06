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
- Only failure events
- No start/end lifecycle events
- No Factor support

### Boundary
- `call(operation, work)` — returns `Outcome<T>`
- `call(operation, tags, work)` — with tags
- Reports failures only (via `reporter.report(failure)`)
- No lifecycle events (onStart/onEnd)
- No fluent API for factors
- Correlation ID only used in failure path
- Success outcomes have no correlation ID

---

## Target State Summary

### Outcome
- `Ok<T>(T value, String correlationId)` — with optional correlation ID
- `Fail<T>(Failure failure, String correlationId)` — with optional correlation ID
- `correlationId()` method returning `Optional<String>`
- `correlationId(String)` method for post-processing

### Factor (new)
- Sealed interface with predefined implementations
- `DaysOfWeek`, `TimeOfDay`, `Region`, `CustomFactor`

### OpReporter
```java
void report(Failure failure);  // standalone, no boundary context
void onStart(String operation, String correlationId, Set<Factor> factors);
void onEnd(Outcome<?> outcome);
void onRetryAttempt(Outcome.Fail<?> failure, int attemptNumber, Duration delay);
void onRetryExhausted(Outcome.Fail<?> failure, int totalAttempts);
```

### Boundary
- Fluent API: `boundary.factors(...).call(operation, work)`
- Generates correlation ID for every call
- Emits `onStart` before work
- Emits `onEnd` after work (success or failure)
- Post-processes returned Outcome with `correlationId()`
- Success outcomes carry correlation ID

### BoundaryContext (new)
- Holds factors for a single call
- Thread-safe (no shared mutable state)

---

## Implementation Checklist

### Phase 1: Core Types ✓

- [x] **1.1 Create Factor sealed interface**
  - File: `src/main/java/org/javai/outcome/Factor.java`
  - Sealed interface with `String name()` method
  - Permits: `DaysOfWeek`, `TimeOfDay`, `Region`, `CustomFactor`

- [x] **1.2 Create DaysOfWeek record**
  - File: `src/main/java/org/javai/outcome/DaysOfWeek.java`
  - `record DaysOfWeek(Set<DayOfWeek> days) implements Factor`
  - Factory methods: `of(DayOfWeek...)`, `weekdays()`, `weekends()`

- [x] **1.3 Create TimeOfDay record**
  - File: `src/main/java/org/javai/outcome/TimeOfDay.java`
  - `record TimeOfDay(int fromHour, int toHour) implements Factor`
  - Factory methods: `businessHours()`, `offHours()`

- [x] **1.4 Create Region record**
  - File: `src/main/java/org/javai/outcome/Region.java`
  - `record Region(String value) implements Factor`

- [x] **1.5 Create CustomFactor record**
  - File: `src/main/java/org/javai/outcome/CustomFactor.java`
  - `record CustomFactor(String name, String value) implements Factor`

- [x] **1.6 Add tests for Factor types**
  - File: `src/test/java/org/javai/outcome/FactorTest.java`

### Phase 2: Outcome Enhancement ✓

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

### Phase 3: OpReporter Enhancement

- [ ] **3.1 Add onStart method to OpReporter**
  - `void onStart(String operation, String correlationId, Set<Factor> factors)`
  - Default implementation: no-op

- [ ] **3.2 Add onEnd method to OpReporter**
  - `void onEnd(Outcome<?> outcome)`
  - Default implementation: no-op

- [ ] **3.3 Update onRetryAttempt signature**
  - Change from `(Failure, int, Duration)` to `(Outcome.Fail<?>, int, Duration)`
  - Correlation ID now comes from Outcome

- [ ] **3.4 Update onRetryExhausted signature**
  - Change from `(Failure, int)` to `(Outcome.Fail<?>, int)`
  - Correlation ID now comes from Outcome

- [ ] **3.5 Deprecate old method signatures**
  - Keep `reportRetryAttempt(Failure, int, Duration)` as deprecated
  - Keep `reportRetryExhausted(Failure, int)` as deprecated
  - Provide default implementations that delegate

- [ ] **3.6 Add tests for OpReporter lifecycle methods**
  - File: `src/test/java/org/javai/outcome/ops/OpReporterLifecycleTest.java`

### Phase 4: Boundary Enhancement

- [ ] **4.1 Create BoundaryContext class**
  - File: `src/main/java/org/javai/outcome/boundary/BoundaryContext.java`
  - Holds: `Boundary boundary`, `Set<Factor> factors`
  - Has `call(operation, work)` method

- [ ] **4.2 Add factors() method to Boundary**
  - `BoundaryContext factors(Factor... factors)`
  - Returns new BoundaryContext with factors

- [ ] **4.3 Add correlation ID generation to Boundary**
  - Generate UUID-based correlation ID at start of each call
  - Or use supplier if configured

- [ ] **4.4 Emit onStart event**
  - Call `reporter.onStart(operation, correlationId, factors)` before work

- [ ] **4.5 Emit onEnd event**
  - Call `reporter.onEnd(outcome)` after work completes
  - Both success and failure paths

- [ ] **4.6 Post-process Outcome with correlation ID**
  - Call `outcome.correlationId(correlationId)` before returning
  - Applies to both Ok and Fail outcomes

- [ ] **4.7 Update handleException to use new model**
  - Create `Outcome.Fail` with correlation ID
  - Use new `onRetryAttempt` signature in Retrier integration

- [ ] **4.8 Add tests for Boundary lifecycle events**
  - File: `src/test/java/org/javai/outcome/boundary/BoundaryLifecycleTest.java`

- [ ] **4.9 Add tests for Boundary fluent API**
  - File: `src/test/java/org/javai/outcome/boundary/BoundaryFluentApiTest.java`

### Phase 5: Update Existing OpReporter Implementations

- [ ] **5.1 Update Log4jOpReporter**
  - Implement `onStart`, `onEnd` methods
  - Update retry methods to use new signatures

- [ ] **5.2 Update MetricsOpReporter**
  - Implement `onStart`, `onEnd` methods
  - Include correlation ID and factors in JSON output

- [ ] **5.3 Update SlackOpReporter**
  - Implement `onStart`, `onEnd` methods (if applicable)
  - Or keep as failure-only reporter

- [ ] **5.4 Update TeamsOpReporter**
  - Implement `onStart`, `onEnd` methods (if applicable)
  - Or keep as failure-only reporter

- [ ] **5.5 Update CompositeOpReporter**
  - Delegate new lifecycle methods to all reporters

- [ ] **5.6 Update tests for all reporters**

### Phase 6: Update Retrier Integration

- [ ] **6.1 Update Retrier to use new OpReporter methods**
  - Use `onRetryAttempt(Outcome.Fail<?>, int, Duration)`
  - Use `onRetryExhausted(Outcome.Fail<?>, int)`

- [ ] **6.2 Ensure correlation ID flows through retries**
  - Same correlation ID for all attempts in a retry sequence

- [ ] **6.3 Update Retrier tests**

### Phase 7: Documentation

- [ ] **7.1 Update README.md**
  - Document new fluent API
  - Document lifecycle events
  - Document factors

- [ ] **7.2 Update Javadoc**
  - All new classes and methods
  - Update existing Javadoc for changed signatures

- [ ] **7.3 Create migration guide**
  - For users upgrading from previous version
  - Document deprecated methods

---

## Breaking Changes

| Change | Impact | Migration |
|--------|--------|-----------|
| `Outcome.Ok` record signature | Existing pattern matches may break | Add `correlationId` to pattern or use accessor |
| `Outcome.Fail` record signature | Existing pattern matches may break | Add `correlationId` to pattern or use accessor |
| `OpReporter.reportRetryAttempt` signature | Implementations need update | Deprecated overload provided |
| `OpReporter.reportRetryExhausted` signature | Implementations need update | Deprecated overload provided |

---

## Non-Breaking Additions

| Addition | Description |
|----------|-------------|
| `Factor` sealed interface | New type for specifying factors |
| `boundary.factors(...)` | Fluent API for specifying factors |
| `OpReporter.onStart()` | New lifecycle event (default no-op) |
| `OpReporter.onEnd()` | New lifecycle event (default no-op) |
| `Outcome.correlationId()` | New accessor returning `Optional<String>` |
| `Outcome.correlationId(String)` | New method returning Outcome with correlation ID |

---

## Open Questions

1. **Package location for Factor types**: Should they be in `org.javai.outcome` or `org.javai.outcome.factor`?

2. **Backward compatibility for Outcome records**: Adding `correlationId` to records changes their canonical constructor. Should we provide a compatibility layer?

3. **Default correlation ID generation**: Should Boundary always generate UUIDs, or should it be configurable?

---

## Notes

- Post-processing approach means existing lambda signatures don't change
- Factors are optional — calls without `factors()` work as before
- Lifecycle events have default no-op implementations for backward compatibility
