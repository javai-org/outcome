package org.javai.outcome;

/**
 * Represents a covariate that affects an operation's behavior.
 *
 * <p>Covariates define subgroup membership criteria for statistical analysis.
 * They are not arbitrary metadata — they have analytical meaning. A covariate
 * like {@code DaysOfWeek.weekdays()} declares: "Events occurring on these
 * days belong to this subgroup."
 *
 * <p>Covariates are distinct from tags (general observability metadata):
 * <ul>
 *   <li>Covariates define statistical subgroups; tags are general metadata</li>
 *   <li>Covariates are type-safe; tags are string maps</li>
 *   <li>Covariates must have bounded cardinality; tags can be high-cardinality</li>
 * </ul>
 *
 * <p>Usage with Boundary:
 * <pre>{@code
 * boundary.covariates(DaysOfWeek.weekdays(), TimeOfDay.businessHours())
 *     .call("UserService.fetch", () -> userService.fetch(id));
 * }</pre>
 *
 * <p>Note: This type aligns with PUnit's definition of covariates for
 * consistent terminology across the ecosystem.
 *
 * @see DaysOfWeek
 * @see TimeOfDay
 * @see Region
 * @see CustomCovariate
 */
public sealed interface Covariate permits DaysOfWeek, TimeOfDay, Region, CustomCovariate {

    /**
     * Returns the name of this covariate for reporting and analysis.
     *
     * @return the covariate name
     */
    String name();
}
