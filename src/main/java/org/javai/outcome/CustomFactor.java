package org.javai.outcome;

import java.util.Objects;

/**
 * A custom factor for domain-specific subgroup membership.
 *
 * <p>Use this when the predefined factors ({@link DaysOfWeek}, {@link TimeOfDay},
 * {@link Region}) don't cover your needs:
 *
 * <pre>{@code
 * // Customer tier
 * new CustomFactor("customer_tier", "premium")
 * new CustomFactor("customer_tier", "free")
 *
 * // Deployment version
 * new CustomFactor("deployment_version", "v2.3.1")
 *
 * // Feature flag
 * new CustomFactor("feature_flag", "new_checkout_enabled")
 * }</pre>
 *
 * <p>Custom factors should have bounded cardinality. High-cardinality
 * factors (e.g., user IDs) will poison statistical analysis.
 *
 * @param name the factor name
 * @param value the factor value
 */
public record CustomFactor(String name, String value) implements Factor {

    /**
     * Canonical constructor with validation.
     */
    public CustomFactor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    @Override
    public String name() {
        return name;
    }
}
