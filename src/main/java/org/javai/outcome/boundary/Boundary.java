package org.javai.outcome.boundary;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.javai.outcome.Failure;
import org.javai.outcome.FailureKind;
import org.javai.outcome.FailureStability;
import org.javai.outcome.NotificationIntent;
import org.javai.outcome.Outcome;
import org.javai.outcome.ops.OpReporter;

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
 * // Simple usage for testing or prototyping
 * Boundary boundary = Boundary.silent();
 *
 * // Production usage with reporting
 * Boundary boundary = Boundary.withReporter(myReporter);
 *
 * // Full control
 * Boundary boundary = Boundary.of(classifier, reporter);
 *
 * Outcome<Response> result = boundary.call(
 *     "HttpClient.send",
 *     () -> httpClient.send(request)
 * );
 * }</pre>
 */
public final class Boundary {

    private static final FailureClassifier DEFAULT_CLASSIFIER = new BoundaryFailureClassifier();

    private final FailureClassifier classifier;
    private final OpReporter reporter;
    private final Supplier<String> correlationIdSupplier;

    /**
     * Creates a silent Boundary that classifies failures but does not report them.
     *
     * <p>Useful for testing, prototyping, or simple scripts where operational
     * reporting is not needed.
     *
     * @return a Boundary with default classification and no reporting
     */
    public static Boundary silent() {
        return new Boundary(DEFAULT_CLASSIFIER, OpReporter.noOp());
    }

    /**
     * Creates a Boundary with default classification and the specified reporter.
     *
     * <p>This is the recommended factory for production use when the default
     * {@link BoundaryFailureClassifier} is sufficient.
     *
     * @param reporter the reporter for failure notifications
     * @return a Boundary with default classification and custom reporting
     */
    public static Boundary withReporter(OpReporter reporter) {
        return new Boundary(DEFAULT_CLASSIFIER, reporter);
    }

    /**
     * Creates a Boundary with custom classification and reporting.
     *
     * <p>Use this factory when you need full control over both failure
     * classification and reporting behavior.
     *
     * @param classifier the classifier for translating exceptions to failures
     * @param reporter the reporter for failure notifications
     * @return a fully configured Boundary
     */
    public static Boundary of(FailureClassifier classifier, OpReporter reporter) {
        return new Boundary(classifier, reporter);
    }

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
                notificationIntent,
                null
        );

        reporter.report(failure);
        return Outcome.fail(failure);
    }

    private NotificationIntent determineNotificationIntent(FailureKind kind) {
        return switch (kind.category()) {
            case RECOVERABLE -> kind.stability() == FailureStability.TRANSIENT
                    ? NotificationIntent.OBSERVE
                    : NotificationIntent.ALERT;
            case DEFECT -> NotificationIntent.ALERT;
            case TERMINAL -> NotificationIntent.PAGE;
        };
    }
}
