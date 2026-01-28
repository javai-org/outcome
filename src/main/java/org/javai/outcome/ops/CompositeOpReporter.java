package org.javai.outcome.ops;

import org.javai.outcome.Failure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * An {@link OpReporter} that delegates to multiple reporters.
 *
 * <p>All configured reporters receive every call. If a reporter throws an exception,
 * it is caught and logged to stderr, allowing remaining reporters to execute.
 *
 * <p>Example usage:
 * <pre>{@code
 * OpReporter reporter = CompositeOpReporter.of(
 *     new Log4jOpReporter(),
 *     new SlackOpReporter()
 * );
 *
 * // Or using the builder for more control:
 * OpReporter reporter = CompositeOpReporter.builder()
 *     .add(new Log4jOpReporter())
 *     .add(new SlackOpReporter())
 *     .build();
 * }</pre>
 */
public final class CompositeOpReporter implements OpReporter {

	private final List<OpReporter> reporters;

	private CompositeOpReporter(List<OpReporter> reporters) {
		this.reporters = List.copyOf(reporters);
	}

	/**
	 * Creates a composite reporter from the given reporters.
	 *
	 * @param reporters the reporters to delegate to
	 * @return a composite that fans out to all given reporters
	 */
	public static CompositeOpReporter of(OpReporter... reporters) {
		return new CompositeOpReporter(Arrays.asList(reporters));
	}

	/**
	 * Creates a composite reporter from a collection of reporters.
	 *
	 * @param reporters the reporters to delegate to
	 * @return a composite that fans out to all given reporters
	 */
	public static CompositeOpReporter of(Collection<? extends OpReporter> reporters) {
		return new CompositeOpReporter(new ArrayList<>(reporters));
	}

	/**
	 * Creates a builder for constructing a composite reporter.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public void report(Failure failure) {
		for (OpReporter reporter : reporters) {
			try {
				reporter.report(failure);
			} catch (Exception e) {
				logReporterError("report", reporter, e);
			}
		}
	}

	@Override
	public void reportRetryAttempt(Failure failure, int attemptNumber) {
		for (OpReporter reporter : reporters) {
			try {
				reporter.reportRetryAttempt(failure, attemptNumber);
			} catch (Exception e) {
				logReporterError("reportRetryAttempt", reporter, e);
			}
		}
	}

	@Override
	public void reportRetryExhausted(Failure failure, int totalAttempts) {
		for (OpReporter reporter : reporters) {
			try {
				reporter.reportRetryExhausted(failure, totalAttempts);
			} catch (Exception e) {
				logReporterError("reportRetryExhausted", reporter, e);
			}
		}
	}

	/**
	 * Returns the number of reporters in this composite.
	 */
	public int size() {
		return reporters.size();
	}

	private static void logReporterError(String method, OpReporter reporter, Exception e) {
		System.err.println("OpReporter." + method + " failed for " +
			reporter.getClass().getName() + ": " + e.getMessage());
	}

	/**
	 * Builder for creating a {@link CompositeOpReporter}.
	 */
	public static final class Builder {
		private final List<OpReporter> reporters = new ArrayList<>();

		private Builder() {}

		/**
		 * Adds a reporter to the composite.
		 *
		 * @param reporter the reporter to add
		 * @return this builder
		 */
		public Builder add(OpReporter reporter) {
			if (reporter != null) {
				reporters.add(reporter);
			}
			return this;
		}

		/**
		 * Adds multiple reporters to the composite.
		 *
		 * @param reporters the reporters to add
		 * @return this builder
		 */
		public Builder addAll(Collection<? extends OpReporter> reporters) {
			for (OpReporter reporter : reporters) {
				add(reporter);
			}
			return this;
		}

		/**
		 * Conditionally adds a reporter based on a flag.
		 *
		 * @param condition if true, the reporter is added
		 * @param reporter the reporter to add
		 * @return this builder
		 */
		public Builder addIf(boolean condition, OpReporter reporter) {
			if (condition) {
				add(reporter);
			}
			return this;
		}

		/**
		 * Builds the composite reporter.
		 *
		 * @return the composite reporter
		 */
		public CompositeOpReporter build() {
			return new CompositeOpReporter(reporters);
		}
	}
}
