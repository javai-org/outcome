package org.javai.outcome;

/**
 * Represents a factor that affects an operation's behavior.
 *
 * <p>Factors define subgroup membership criteria for statistical analysis.
 * They are not arbitrary metadata — they have analytical meaning. A factor
 * like {@code DaysOfWeek.weekdays()} declares: "Events occurring on these
 * days belong to this subgroup."
 *
 * <p>Factors are distinct from tags (general observability metadata):
 * <ul>
 *   <li>Factors define statistical subgroups; tags are general metadata</li>
 *   <li>Factors are type-safe; tags are string maps</li>
 *   <li>Factors must have bounded cardinality; tags can be high-cardinality</li>
 * </ul>
 *
 * <p>Usage with Boundary:
 * <pre>{@code
 * boundary.factors(DaysOfWeek.weekdays(), TimeOfDay.businessHours())
 *     .call("UserService.fetch", () -> userService.fetch(id));
 * }</pre>
 *
 * <p>Note: Outcome uses "factors" (developer-friendly terminology). Statistical
 * consumers like Signal may use "covariates" internally.
 *
 * @see DaysOfWeek
 * @see TimeOfDay
 * @see Region
 * @see CustomFactor
 */
public sealed interface Factor permits DaysOfWeek, TimeOfDay, Region, CustomFactor {

    /**
     * Returns the name of this factor for reporting and analysis.
     *
     * @return the factor name
     */
    String name();
}
