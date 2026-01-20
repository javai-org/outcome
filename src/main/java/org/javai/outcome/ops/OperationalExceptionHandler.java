package org.javai.outcome.ops;

import org.javai.outcome.*;
import org.javai.outcome.boundary.FailureClassifier;

import java.lang.Thread.UncaughtExceptionHandler;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Catches uncaught exceptions (defects) at the top of the stack and reports them to operations.
 *
 * <p>This handler is designed to work with the Boundary pattern: the Boundary lets
 * RuntimeExceptions propagate (since they represent defects, not operational failures),
 * and this handler catches them at the thread level for reporting before the thread dies.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * OperationalExceptionHandler handler = new OperationalExceptionHandler(classifier, reporter);
 * handler.installAsDefault();
 * }</pre>
 *
 * <p>For thread pools:</p>
 * <pre>{@code
 * ExecutorService executor = Executors.newFixedThreadPool(10, handler.threadFactory("worker"));
 * }</pre>
 */
public final class OperationalExceptionHandler implements UncaughtExceptionHandler {

    private final FailureClassifier classifier;
    private final OpReporter reporter;
    private final Supplier<String> correlationIdSupplier;

    public OperationalExceptionHandler(FailureClassifier classifier, OpReporter reporter) {
        this(classifier, reporter, () -> null);
    }

    public OperationalExceptionHandler(
            FailureClassifier classifier,
            OpReporter reporter,
            Supplier<String> correlationIdSupplier
    ) {
        this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
        this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
        this.correlationIdSupplier = Objects.requireNonNull(correlationIdSupplier, "correlationIdSupplier must not be null");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String operation = "UncaughtException:" + thread.getName();

        FailureKind kind = classifier.classify(operation, throwable);

        // Defects that crash threads should page someone
        NotificationIntent notification = determineNotificationIntent(kind);

        Failure failure = new Failure(
                kind,
                operation,
                Instant.now(),
                correlationIdSupplier.get(),
                Map.of("thread.name", thread.getName(), "thread.id", String.valueOf(thread.threadId())),
                notification
        );

        reporter.report(failure);
    }

    private NotificationIntent determineNotificationIntent(FailureKind kind) {
        // Uncaught exceptions are serious—they killed a thread
        return switch (kind.category()) {
            case RECOVERABLE -> NotificationIntent.ALERT;
            case DEFECT -> NotificationIntent.PAGE;
            case TERMINAL -> NotificationIntent.PAGE;
        };
    }

    /**
     * Installs this handler as the default for all threads.
     */
    public void installAsDefault() {
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    /**
     * Installs this handler on a specific thread.
     */
    public void installOn(Thread thread) {
        thread.setUncaughtExceptionHandler(this);
    }

    /**
     * Creates a ThreadFactory that installs this handler on all created threads.
     * Useful for ExecutorService configuration.
     */
    public ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setUncaughtExceptionHandler(this);
            return thread;
        };
    }

    /**
     * Creates a named ThreadFactory that installs this handler on all created threads.
     */
    public ThreadFactory threadFactory(String namePrefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
                thread.setUncaughtExceptionHandler(OperationalExceptionHandler.this);
                return thread;
            }
        };
    }
}
