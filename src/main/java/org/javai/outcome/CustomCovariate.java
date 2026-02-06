package org.javai.outcome;

import java.util.Objects;

/**
 * A custom covariate for domain-specific subgroup membership.
 *
 * <p>Use this when the predefined covariates ({@link DaysOfWeek}, {@link TimeOfDay},
 * {@link Region}) don't cover your needs:
 *
 * <pre>{@code
 * // Customer tier
 * new CustomCovariate("customer_tier", "premium")
 * new CustomCovariate("customer_tier", "free")
 *
 * // Deployment version
 * new CustomCovariate("deployment_version", "v2.3.1")
 *
 * // Feature flag
 * new CustomCovariate("feature_flag", "new_checkout_enabled")
 * }</pre>
 *
 * <p>Custom covariates should have bounded cardinality. High-cardinality
 * covariates (e.g., user IDs) will poison statistical analysis.
 *
 * @param name the covariate name
 * @param value the covariate value
 */
public record CustomCovariate(String name, String value) implements Covariate {

    /**
     * Canonical constructor with validation.
     */
    public CustomCovariate {
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
