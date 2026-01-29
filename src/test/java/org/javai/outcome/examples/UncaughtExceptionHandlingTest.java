package org.javai.outcome.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.javai.outcome.Failure;
import org.javai.outcome.FailureType;
import org.javai.outcome.ops.DefectClassifier;
import org.javai.outcome.ops.OperationalExceptionHandler;
import org.junit.jupiter.api.Test;

/**
 * Demonstrates using OperationalExceptionHandler to catch uncaught exceptions.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>{@link OperationalExceptionHandler} implements {@link Thread.UncaughtExceptionHandler}</li>
 *   <li>Catches exceptions that escape thread execution (defects)</li>
 *   <li>Reports them via OpReporter for operational visibility</li>
 *   <li>Can be installed on individual threads or as a default for all threads</li>
 * </ul>
 *
 * <p>This is the "last line of defense" for exceptions that escape the Boundary pattern.
 */
public class UncaughtExceptionHandlingTest {

	@Test
	void uncaughtExceptionIsReportedAsDefect() throws InterruptedException {
		// Collect reported failures for assertions
		List<Failure> reported = new ArrayList<>();

		// Set up handler with DefectClassifier (classifies all exceptions as DEFECT)
		OperationalExceptionHandler handler = new OperationalExceptionHandler(
				new DefectClassifier(),
				reported::add
		);

		// Create a thread that will throw an uncaught exception
		Thread thread = new Thread(() -> {
			throw new IllegalStateException("Unexpected condition in worker thread");
		});

		// Install the handler on this specific thread
		handler.installOn(thread);

		// Run the thread and wait for it to complete
		thread.start();
		thread.join();

		// Verify the exception was caught and reported
		assertThat(reported).hasSize(1);

		Failure failure = reported.getFirst();
		assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
		assertThat(failure.message()).contains("Unexpected condition in worker thread");
		assertThat(failure.operation()).startsWith("UncaughtException:");
	}

	@Test
	void handlerEnrichesFailureWithThreadContext() throws InterruptedException {
		List<Failure> reported = new ArrayList<>();

		OperationalExceptionHandler handler = new OperationalExceptionHandler(
				new DefectClassifier(),
				reported::add
		);

		Thread thread = new Thread(() -> {
			throw new NullPointerException("Oops");
		}, "my-worker-thread");

		handler.installOn(thread);
		thread.start();
		thread.join();

		assertThat(reported).hasSize(1);

		Failure failure = reported.getFirst();

		// Handler adds thread context as tags
		assertThat(failure.tags()).containsEntry("thread.name", "my-worker-thread");
		assertThat(failure.tags()).containsKey("thread.id");

		// Operation includes thread name
		assertThat(failure.operation()).isEqualTo("UncaughtException:my-worker-thread");
	}
}
