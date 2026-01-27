package org.javai.outcome.ops;

import org.javai.outcome.Failure;
import org.javai.outcome.FailureId;
import org.javai.outcome.boundary.FailureClassifier;

/**
 * Classifies uncaught exceptions (defects) for use with {@link OperationalExceptionHandler}.
 *
 * <p>This classifier handles RuntimeExceptions that propagated through the application
 * without being caught. These represent defects — bugs or misconfigurations that
 * require human intervention to fix.
 *
 * <p>All failures produced by this classifier are DEFECT type with no retry hint,
 * since retrying a defect will not help.
 *
 * <p>This classifier should not be used for checked exceptions. Use {@link
 * org.javai.outcome.boundary.BoundaryFailureClassifier} for Boundary exception handling.
 */
public class DefectClassifier implements FailureClassifier {

    @Override
    public Failure classify(String operation, Throwable t) {
        if (t instanceof NullPointerException) {
            return defect("null_pointer", "Null pointer", operation, t);
        }

        if (t instanceof IllegalArgumentException) {
            return defect("illegal_argument", "Illegal argument", operation, t);
        }

        if (t instanceof IllegalStateException) {
            return defect("illegal_state", "Illegal state", operation, t);
        }

        if (t instanceof UnsupportedOperationException) {
            return defect("unsupported_operation", "Unsupported operation", operation, t);
        }

        if (t instanceof IndexOutOfBoundsException) {
            return defect("index_out_of_bounds", "Index out of bounds", operation, t);
        }

        if (t instanceof ClassCastException) {
            return defect("class_cast", "Class cast error", operation, t);
        }

        if (t instanceof ArithmeticException) {
            return defect("arithmetic", "Arithmetic error", operation, t);
        }

        // Fallback for any other uncaught exception
        return classifyUnknownDefect(operation, t);
    }

    private static Failure defect(String code, String prefix, String operation, Throwable t) {
        return Failure.defect(
                FailureId.of("defect", code),
                messageFor(prefix, t),
                operation,
                t
        );
    }

    private static Failure classifyUnknownDefect(String operation, Throwable t) {
        String message = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        return Failure.defect(
                FailureId.of("defect", "uncategorized"),
                message,
                operation,
                t
        );
    }

    private static String messageFor(String prefix, Throwable t) {
        return prefix + ": " + t.getMessage();
    }
}
