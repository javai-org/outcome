package org.javai.outcome;

import java.util.Objects;

/**
 * A namespaced, stable identifier for a type of failure.
 *
 * @param namespace The domain or subsystem (e.g., "http", "jdbc", "auth")
 * @param name The specific failure type within that namespace (e.g., "timeout", "connection_refused")
 */
public record FailureCode(String namespace, String name) {

    public FailureCode {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public static FailureCode of(String namespace, String name) {
        return new FailureCode(namespace, name);
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }
}
