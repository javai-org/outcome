package org.javai.outcome;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents the outcome of an operation that may fail.
 * Either {@link Ok} containing a successful value, or {@link Fail} containing a {@link Failure}.
 *
 * @param <T> The type of the successful value
 */
public sealed interface  Outcome<T> permits Outcome.Ok, Outcome.Fail {

    /**
     * A successful outcome containing a value.
     */
    record Ok<T>(T value) implements Outcome<T> {
        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public boolean isFail() {
            return false;
        }

        @Override
        public T getOrThrow() {
            return value;
        }

        @Override
        public T getOrElse(T defaultValue) {
            return value;
        }

        @Override
        public T getOrElseGet(Supplier<? extends T> supplier) {
            return value;
        }

        @Override
        public <U> Outcome<U> map(Function<? super T, ? extends U> mapper) {
            Objects.requireNonNull(mapper);
            return new Ok<>(mapper.apply(value));
        }

        @Override
        public <U> Outcome<U> flatMap(Function<? super T, ? extends Outcome<U>> mapper) {
            Objects.requireNonNull(mapper);
            return mapper.apply(value);
        }

        @Override
        public Outcome<T> recover(Function<? super Failure, ? extends T> recovery) {
            return this;
        }

        @Override
        public Outcome<T> recoverWith(Function<? super Failure, ? extends Outcome<T>> recovery) {
            return this;
        }
    }

    /**
     * A failed outcome containing failure details.
     */
    record Fail<T>(Failure failure) implements Outcome<T> {
        public Fail {
            Objects.requireNonNull(failure, "failure must not be null");
        }

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public boolean isFail() {
            return true;
        }

        @Override
        public T getOrThrow() {
            throw new OutcomeFailedException(failure);
        }

        @Override
        public T getOrElse(T defaultValue) {
            return defaultValue;
        }

        @Override
        public T getOrElseGet(Supplier<? extends T> supplier) {
            Objects.requireNonNull(supplier);
            return supplier.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Outcome<U> map(Function<? super T, ? extends U> mapper) {
            return (Outcome<U>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <U> Outcome<U> flatMap(Function<? super T, ? extends Outcome<U>> mapper) {
            return (Outcome<U>) this;
        }

        @Override
        public Outcome<T> recover(Function<? super Failure, ? extends T> recovery) {
            Objects.requireNonNull(recovery);
            return new Ok<>(recovery.apply(failure));
        }

        @Override
        public Outcome<T> recoverWith(Function<? super Failure, ? extends Outcome<T>> recovery) {
            Objects.requireNonNull(recovery);
            return recovery.apply(failure);
        }
    }

    // Query methods
    boolean isOk();
    boolean isFail();

    // Value extraction
    T getOrThrow();
    T getOrElse(T defaultValue);
    T getOrElseGet(Supplier<? extends T> supplier);

    // Transformations
    <U> Outcome<U> map(Function<? super T, ? extends U> mapper);
    <U> Outcome<U> flatMap(Function<? super T, ? extends Outcome<U>> mapper);

    // Recovery
    Outcome<T> recover(Function<? super Failure, ? extends T> recovery);
    Outcome<T> recoverWith(Function<? super Failure, ? extends Outcome<T>> recovery);

    // Static factories
    static Outcome<Void> ok() {
        return new Ok<>(null);
    }

    static <T> Outcome<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Outcome<T> fail(Failure failure) {
        return new Fail<>(failure);
    }

    /**
     * Creates a failed outcome with a simple failure code name and message.
     * Uses the default namespace "org.javai.outcome" and treats the failure as a defect.
     *
     * @param name The failure code name (e.g., "validation_failed", "missing_data")
     * @param message Human-readable description of what went wrong
     * @param <T> The type parameter for the outcome
     * @return A failed outcome
     */
    static <T> Outcome<T> fail(String name, String message) {
        return fail("org.javai.outcome", name, message);
    }

    /**
     * Creates a failed outcome with a namespaced failure code and message.
     * Treats the failure as a defect.
     *
     * @param namespace The failure code namespace (e.g., "myapp.orders", "http")
     * @param name The failure code name (e.g., "validation_failed", "missing_data")
     * @param message Human-readable description of what went wrong
     * @param <T> The type parameter for the outcome
     * @return A failed outcome
     */
    static <T> Outcome<T> fail(String namespace, String name, String message) {
        return new Fail<>(Failure.defect(
                FailureId.of(namespace, name),
                message,
                "unspecified",
                null));
    }

    /**
     * Creates a failed outcome using the class's package name as the failure code namespace.
     * Treats the failure as a defect.
     *
     * @param clazz The class whose package name will be used as the namespace
     * @param name The failure code name (e.g., "validation_failed", "missing_data")
     * @param message Human-readable description of what went wrong
     * @param <T> The type parameter for the outcome
     * @return A failed outcome
     */
    static <T> Outcome<T> fail(Class<?> clazz, String name, String message) {
        return fail(clazz.getPackageName(), name, message);
    }
}
