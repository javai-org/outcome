package org.javai.outcome.boundary;

/**
 * A supplier that may throw a checked exception.
 * Used by {@link Boundary} to wrap calls to third-party APIs.
 *
 * @param <T> The type of value supplied
 * @param <E> The type of exception that may be thrown
 */
@FunctionalInterface
public interface ThrowingSupplier<T, E extends Exception> {

    T get() throws E;
}
