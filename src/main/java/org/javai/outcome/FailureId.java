package org.javai.outcome;

import java.util.Objects;

/**
 * A namespaced, stable identifier for a type of failure.
 *
 * @param namespace The domain or subsystem (e.g., "http", "jdbc", "auth")
 * @param name The specific failure type within that namespace (e.g., "timeout", "connection_refused")
 */
public record FailureId(String namespace, String name) {

    public FailureId {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * Creates a FailureId with the given namespace and name.
     */
    public static FailureId of(String namespace, String name) {
        return new FailureId(namespace, name);
    }

    /**
     * Creates a FailureId using the class's simple name as the namespace.
     *
     * @param source The class that originated the failure
     * @param name The specific failure type
     */
    public static FailureId of(Class<?> source, String name) {
        Objects.requireNonNull(source, "source must not be null");
        return new FailureId(source.getSimpleName(), name);
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }
}
