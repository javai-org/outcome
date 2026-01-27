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
        Failure failure = classifier.classify("Process", new NullPointerException("oops"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "null_pointer"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void illegalArgument_isDefect() {
        Failure failure = classifier.classify("Validate",
                new IllegalArgumentException("bad input"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "illegal_argument"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void illegalState_isDefect() {
        Failure failure = classifier.classify("Execute",
                new IllegalStateException("not initialized"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "illegal_state"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void unsupportedOperation_isDefect() {
        Failure failure = classifier.classify("Call",
                new UnsupportedOperationException("not implemented"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "unsupported_operation"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void indexOutOfBounds_isDefect() {
        Failure failure = classifier.classify("Access",
                new IndexOutOfBoundsException("Index 5 out of bounds"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "index_out_of_bounds"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void classCast_isDefect() {
        Failure failure = classifier.classify("Cast",
                new ClassCastException("String cannot be cast to Integer"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "class_cast"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void arithmetic_isDefect() {
        Failure failure = classifier.classify("Divide",
                new ArithmeticException("/ by zero"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "arithmetic"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void unknownRuntimeException_isDefect() {
        Failure failure = classifier.classify("Unknown",
                new RuntimeException("mystery error"));

        assertThat(failure.id()).isEqualTo(FailureId.of("defect", "uncategorized"));
        assertThat(failure.type()).isEqualTo(FailureType.DEFECT);
    }

    @Test
    void exception_isPopulated() {
        Exception ex = new NullPointerException("test");
        Failure failure = classifier.classify("Op", ex);

        assertThat(failure.exception()).isNotNull();
        assertThat(failure.exception()).isEqualTo(ex);
        assertThat(failure.exception().getMessage()).isEqualTo("test");
    }
}
