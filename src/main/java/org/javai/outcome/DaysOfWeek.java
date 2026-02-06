package org.javai.outcome;

import java.time.DayOfWeek;
import java.util.Objects;
import java.util.Set;

import static java.time.DayOfWeek.*;

/**
 * A factor representing days of the week for subgroup membership.
 *
 * <p>Events occurring on days in this set belong to this subgroup.
 * Use this to stratify analysis by day patterns:
 *
 * <pre>{@code
 * // Weekday subgroup
 * DaysOfWeek.weekdays()
 *
 * // Weekend subgroup
 * DaysOfWeek.weekends()
 *
 * // Weekly sampling (Mondays only)
 * DaysOfWeek.of(MONDAY)
 *
 * // Custom grouping
 * DaysOfWeek.of(MONDAY, WEDNESDAY, FRIDAY)
 * }</pre>
 *
 * @param days the days that define this subgroup
 */
public record DaysOfWeek(Set<DayOfWeek> days) implements Factor {

    /**
     * Canonical constructor with validation.
     */
    public DaysOfWeek {
        Objects.requireNonNull(days, "days must not be null");
        if (days.isEmpty()) {
            throw new IllegalArgumentException("days must not be empty");
        }
        days = Set.copyOf(days);
    }

    /**
     * Creates a DaysOfWeek factor for the specified days.
     *
     * @param days the days to include
     * @return a new DaysOfWeek factor
     */
    public static DaysOfWeek of(DayOfWeek... days) {
        Objects.requireNonNull(days, "days must not be null");
        if (days.length == 0) {
            throw new IllegalArgumentException("days must not be empty");
        }
        return new DaysOfWeek(Set.of(days));
    }

    /**
     * Creates a DaysOfWeek factor for weekdays (Monday through Friday).
     *
     * @return a new DaysOfWeek factor for weekdays
     */
    public static DaysOfWeek weekdays() {
        return new DaysOfWeek(Set.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY));
    }

    /**
     * Creates a DaysOfWeek factor for weekends (Saturday and Sunday).
     *
     * @return a new DaysOfWeek factor for weekends
     */
    public static DaysOfWeek weekends() {
        return new DaysOfWeek(Set.of(SATURDAY, SUNDAY));
    }

    @Override
    public String name() {
        return "days_of_week";
    }
}
