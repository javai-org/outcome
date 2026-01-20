package org.javai.outcome.boundary;

import org.javai.outcome.*;
import org.javai.outcome.ops.OpReporter;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The boundary adapter for integrating third-party APIs that throw checked exceptions.
 * Catches exceptions, classifies them into failures, reports them, and returns Outcome.
 *
 * <p>This is the single point where checked exceptions are translated into the Outcome world.
 * After passing through a Boundary, code operates entirely in outcome-space.</p>
 *
 * <p>RuntimeExceptions (defects) are not caught—they propagate up to be handled by
 * {@link org.javai.outcome.ops.OperationalExceptionHandler} at the top of the stack.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Boundary boundary = new Boundary(classifier, reporter);
 *
 * Outcome<Response> result = boundary.call(
 *     "HttpClient.send",
 *     () -> httpClient.send(request)
 * );
 * }</pre>
 */
public final class Boundary {

    private final FailureClassifier classifier;
    private final OpReporter reporter;
    private final Supplier<String> correlationIdSupplier;

    public Boundary(FailureClassifier classifier, OpReporter reporter) {
        this(classifier, reporter, () -> null);
    }

    public Boundary(FailureClassifier classifier, OpReporter reporter, Supplier<String> correlationIdSupplier) {
        this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
        this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
        this.correlationIdSupplier = Objects.requireNonNull(correlationIdSupplier, "correlationIdSupplier must not be null");
    }

    /**
     * Executes work that may throw checked exceptions, translating any exception into an Outcome.
     *
     * @param operation The operation name for context and reporting
     * @param work The work to execute
     * @return Ok with the result, or Fail with a classified failure
     */
    public <T> Outcome<T> call(String operation, ThrowingSupplier<T, ? extends Exception> work) {
        return call(operation, Map.of(), work);
    }

    /**
     * Executes work with additional tags for observability.
     *
     * @param operation The operation name
     * @param tags Additional metadata tags
     * @param work The work to execute
     * @return Ok with the result, or Fail with a classified failure
     */
    public <T> Outcome<T> call(String operation, Map<String, String> tags, ThrowingSupplier<T, ? extends Exception> work) {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(work, "work must not be null");

        try {
            return Outcome.ok(work.get());
        } catch (RuntimeException e) {
            // Defects propagate—they're not operational failures.
            // They'll be caught by Thread.UncaughtExceptionHandler at the top.
            throw e;
        } catch (Exception e) {
            // Checked exceptions are operational failures → Outcome.Fail
            return handleException(operation, tags, e);
        }
    }

    private <T> Outcome<T> handleException(String operation, Map<String, String> tags, Exception e) {
        Instant occurredAt = Instant.now();
        String correlationId = correlationIdSupplier.get();

        FailureKind kind = classifier.classify(operation, e);
        NotificationIntent notificationIntent = determineNotificationIntent(kind);

        Failure failure = new Failure(
                kind,
                operation,
                occurredAt,
                correlationId,
                tags,
                notificationIntent
        );

        reporter.report(failure);
        return Outcome.fail(failure);
    }

    private NotificationIntent determineNotificationIntent(FailureKind kind) {
        return switch (kind.category()) {
            case OPERATIONAL -> kind.stability() == FailureStability.TRANSIENT
                    ? NotificationIntent.OBSERVE
                    : NotificationIntent.ALERT;
            case DEFECT_OR_MISCONFIGURATION -> NotificationIntent.ALERT;
            case FATAL_ENVIRONMENT -> NotificationIntent.PAGE;
        };
    }
}
