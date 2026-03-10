# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-03-10

### Changed
- `Failure` record fields `exception`, `retryAfter`, and `correlationId` now use `Optional` instead of nullable types
- `RetryPolicy` implementations now respect the failure's `retryAfter` hint via a shared `withDelayStrategy` method, removing duplication between `fixed` and `backoff` policies

### Added
- `HttpStatusException` — checked exception carrying HTTP status code and parsed `Retry-After` hint
- `HttpResponses.requireSuccess(HttpResponse)` — utility to convert non-2xx responses into `HttpStatusException`
- `BoundaryFailureClassifier` support for `HttpStatusException`: classifies 429 and 5xx as transient, other 4xx as permanent, with `Retry-After` propagation

## [0.1.0] - 2025-12-15

Initial release of Outcome — a framework for building action plans based on
natural language inputs.

### Added
- `Outcome` type with success/failure semantics and tracking keys
- `Boundary` for structured failure classification with transient/permanent/correctable types
- Retry support via `Retrier` with configurable strategies and corrective retry
- Operator reporting: `Log4jOpReporter`, `MetricsOpReporter`, `SlackOpReporter`, `TeamsOpReporter`
- `CompositeReporter` for combining multiple reporters
- `DefectClassifier` for mapping exceptions to failure types
- Static convenience methods for low-ceremony usage

[Unreleased]: https://github.com/javai-org/outcome/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/javai-org/outcome/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/javai-org/outcome/releases/tag/v0.1.0
