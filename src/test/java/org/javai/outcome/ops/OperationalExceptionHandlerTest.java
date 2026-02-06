package org.javai.outcome.ops;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.javai.outcome.Failure;
import org.javai.outcome.FailureId;
import org.javai.outcome.FailureType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationalExceptionHandlerTest {

    private List<Failure> reportedFailures;
    private OperationalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        reportedFailures = new ArrayList<>();
        handler = new OperationalExceptionHandler(
                new DefaultDefectClassifier(),
                reportedFailures::add
        );
    }

    @Test
    void uncaughtException_reportsFailure() {
        Thread thread = new Thread(() -> {});
        NullPointerException exception = new NullPointerException("test");

        handler.uncaughtException(thread, exception);

        assertThat(reportedFailures).hasSize(1);
        Failure failure = reportedFailures.getFirst();
        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "null_pointer"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void uncaughtException_includesThreadInfo() {
        Thread thread = Thread.currentThread();

        handler.uncaughtException(thread, new RuntimeException("boom"));

        Failure failure = reportedFailures.getFirst();
        assertThat(failure.operation()).startsWith("UncaughtException:");
        assertThat(failure.tags()).containsKey("thread.name");
        assertThat(failure.tags()).containsKey("thread.id");
    }

    @Test
    void uncaughtException_defect_isClassifiedAsDefect() {
        handler.uncaughtException(Thread.currentThread(), new NullPointerException());

        Failure failure = reportedFailures.getFirst();
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void uncaughtException_unknownRuntime_isDefect() {
        // Unknown RuntimeException → DEFECT (it's an uncaught bug)
        handler.uncaughtException(Thread.currentThread(), new RuntimeException("unknown"));

        Failure failure = reportedFailures.getFirst();
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void uncaughtException_withCorrelationId() {
        OperationalExceptionHandler handlerWithCorrelation = new OperationalExceptionHandler(
                new DefaultDefectClassifier(),
                reportedFailures::add,
                () -> "correlation-xyz"
        );

        handlerWithCorrelation.uncaughtException(Thread.currentThread(), new RuntimeException());

        Failure failure = reportedFailures.getFirst();
        assertThat(failure.correlationId()).isEqualTo("correlation-xyz");
    }

    @Test
    void threadFactory_createsThreadsWithHandler() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread = handler.threadFactory().newThread(() -> {
            latch.countDown();
            throw new IllegalStateException("boom");
        });

        thread.start();
        latch.await(1, TimeUnit.SECONDS);
        thread.join(1000);

        assertThat(reportedFailures).hasSize(1);
        assertThat(reportedFailures.getFirst().id())
                .isEqualTo(FailureId.of("defect", "illegal_state"));
    }

    @Test
    void threadFactory_withNamePrefix() {
        Thread thread = handler.threadFactory("worker").newThread(() -> {});

        assertThat(thread.getName()).startsWith("worker-");
    }

    @Test
    void executorService_integration() throws InterruptedException {
        // Note: submit() catches exceptions in the Future, so we use execute()
        // which lets exceptions propagate to the UncaughtExceptionHandler
        ExecutorService executor = Executors.newSingleThreadExecutor(handler.threadFactory("test"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch canThrow = new CountDownLatch(1);

        executor.execute(() -> {
            started.countDown();
            try {
                canThrow.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new NullPointerException("executor defect");
        });

        started.await(1, TimeUnit.SECONDS);
        canThrow.countDown();
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        // Give the uncaught exception handler time to run
        Thread.sleep(200);

        assertThat(reportedFailures).hasSize(1);
        assertThat(reportedFailures.getFirst().message()).contains("executor defect");
    }
}
