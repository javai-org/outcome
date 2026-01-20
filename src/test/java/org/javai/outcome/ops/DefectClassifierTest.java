package org.javai.outcome.ops;

import org.javai.outcome.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DefectClassifierTest {

    private DefectClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DefectClassifier();
    }

    @Test
    void nullPointer_isDefect() {
        FailureKind kind = classifier.classify("Process", new NullPointerException("oops"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "null_pointer"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.NONE);
    }

    @Test
    void illegalArgument_isDefect() {
        FailureKind kind = classifier.classify("Validate",
                new IllegalArgumentException("bad input"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "illegal_argument"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.NONE);
    }

    @Test
    void illegalState_isDefect() {
        FailureKind kind = classifier.classify("Execute",
                new IllegalStateException("not initialized"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "illegal_state"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
    }

    @Test
    void unsupportedOperation_isDefect() {
        FailureKind kind = classifier.classify("Call",
                new UnsupportedOperationException("not implemented"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "unsupported_operation"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
    }

    @Test
    void indexOutOfBounds_isDefect() {
        FailureKind kind = classifier.classify("Access",
                new IndexOutOfBoundsException("Index 5 out of bounds"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "index_out_of_bounds"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
    }

    @Test
    void classCast_isDefect() {
        FailureKind kind = classifier.classify("Cast",
                new ClassCastException("String cannot be cast to Integer"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "class_cast"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
    }

    @Test
    void arithmetic_isDefect() {
        FailureKind kind = classifier.classify("Divide",
                new ArithmeticException("/ by zero"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "arithmetic"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
    }

    @Test
    void unknownRuntimeException_isDefect() {
        FailureKind kind = classifier.classify("Unknown",
                new RuntimeException("mystery error"));

        assertThat(kind.code()).isEqualTo(FailureCode.of("defect", "uncategorized"));
        assertThat(kind.category()).isEqualTo(FailureCategory.DEFECT);
        assertThat(kind.stability()).isEqualTo(FailureStability.PERMANENT);
        assertThat(kind.retryHint().retryability()).isEqualTo(Retryability.NONE);
    }

    @Test
    void cause_isPopulated() {
        Exception ex = new NullPointerException("test");
        FailureKind kind = classifier.classify("Op", ex);

        assertThat(kind.cause()).isNotNull();
        assertThat(kind.cause().type()).isEqualTo("java.lang.NullPointerException");
        assertThat(kind.cause().detail()).isEqualTo("test");
    }
}
