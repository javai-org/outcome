package org.javai.outcome.ops;

import org.javai.outcome.*;
import org.javai.outcome.boundary.FailureClassifier;

/**
 * Classifies uncaught exceptions (defects) for use with {@link OperationalExceptionHandler}.
 *
 * <p>This classifier handles RuntimeExceptions that propagated through the application
 * without being caught. These represent defects — bugs or misconfigurations that
 * require human intervention to fix.
 *
 * <p>All failures produced by this classifier are {@link FailureCategory#DEFECT}
 * with {@link FailureStability#PERMANENT} and no retry hint, since retrying a
 * defect will not help.
 *
 * <p>This classifier should not be used for checked exceptions. Use {@link
 * org.javai.outcome.boundary.BoundaryFailureClassifier} for Boundary exception handling.
 */
public class DefectClassifier implements FailureClassifier {

    @Override
    public FailureKind classify(String operation, Throwable t) {
        Cause cause = Cause.fromThrowable(t);

        if (t instanceof NullPointerException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "null_pointer"),
                    "Null pointer: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof IllegalArgumentException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "illegal_argument"),
                    "Illegal argument: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof IllegalStateException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "illegal_state"),
                    "Illegal state: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof UnsupportedOperationException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "unsupported_operation"),
                    "Unsupported operation: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof IndexOutOfBoundsException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "index_out_of_bounds"),
                    "Index out of bounds: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof ClassCastException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "class_cast"),
                    "Class cast error: " + t.getMessage(),
                    cause
            );
        }

        if (t instanceof ArithmeticException) {
            return FailureKind.defect(
                    FailureCode.of("defect", "arithmetic"),
                    "Arithmetic error: " + t.getMessage(),
                    cause
            );
        }

        // Fallback for any other uncaught exception
        return FailureKind.defect(
                FailureCode.of("defect", "uncategorized"),
                t.getMessage() != null ? t.getMessage() : t.getClass().getName(),
                cause
        );
    }
}
