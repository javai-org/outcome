package org.javai.outcome.ops;

import java.util.List;
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
public class DefaultDefectClassifier implements FailureClassifier {

    private record DefectMapping(Class<? extends Throwable> type, String code, String prefix) {}

    private static final List<DefectMapping> MAPPINGS = List.of(
            new DefectMapping(NullPointerException.class, "null_pointer", "Null pointer"),
            new DefectMapping(IllegalArgumentException.class, "illegal_argument", "Illegal argument"),
            new DefectMapping(IllegalStateException.class, "illegal_state", "Illegal state"),
            new DefectMapping(UnsupportedOperationException.class, "unsupported_operation", "Unsupported operation"),
            new DefectMapping(IndexOutOfBoundsException.class, "index_out_of_bounds", "Index out of bounds"),
            new DefectMapping(ClassCastException.class, "class_cast", "Class cast error"),
            new DefectMapping(ArithmeticException.class, "arithmetic", "Arithmetic error")
    );

    @Override
    public Failure classify(String operation, Throwable t) {
        return MAPPINGS.stream()
                .filter(m -> m.type().isInstance(t))
                .map(m -> defect(m.code(), m.prefix(), operation, t))
                .findFirst()
                .orElseGet(() -> classifyUnknownDefect(operation, t));
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
