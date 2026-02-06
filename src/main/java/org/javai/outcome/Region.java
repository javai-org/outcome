package org.javai.outcome;

import java.util.Objects;

/**
 * A covariate representing a geographic or logical region for subgroup membership.
 *
 * <p>Events from this region belong to this subgroup. Use this to stratify
 * analysis by deployment region, data center, or other geographic covariates:
 *
 * <pre>{@code
 * // AWS regions
 * new Region("us-east-1")
 * new Region("eu-west-1")
 *
 * // Data centers
 * new Region("dc-london")
 * new Region("dc-tokyo")
 * }</pre>
 *
 * @param value the region identifier
 */
public record Region(String value) implements Covariate {

    /**
     * Canonical constructor with validation.
     */
    public Region {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    @Override
    public String name() {
        return "region";
    }
}
