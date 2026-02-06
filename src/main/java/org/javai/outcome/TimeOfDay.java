package org.javai.outcome;

/**
 * A factor representing a time-of-day range for subgroup membership.
 *
 * <p>Events occurring within this time range belong to this subgroup.
 * Use this to stratify analysis by time patterns:
 *
 * <pre>{@code
 * // Business hours (9am to 5pm)
 * TimeOfDay.businessHours()
 *
 * // Off-hours (5pm to 9am, wraps overnight)
 * TimeOfDay.offHours()
 *
 * // Custom range
 * new TimeOfDay(8, 20)  // 8am to 8pm
 * }</pre>
 *
 * <p>When {@code toHour} is less than {@code fromHour}, the range wraps
 * across midnight (e.g., 17 to 9 means 5pm to 9am).
 *
 * @param fromHour the starting hour (0-23, inclusive)
 * @param toHour the ending hour (0-23, exclusive)
 */
public record TimeOfDay(int fromHour, int toHour) implements Factor {

    /**
     * Canonical constructor with validation.
     */
    public TimeOfDay {
        if (fromHour < 0 || fromHour > 23) {
            throw new IllegalArgumentException("fromHour must be between 0 and 23, got: " + fromHour);
        }
        if (toHour < 0 || toHour > 23) {
            throw new IllegalArgumentException("toHour must be between 0 and 23, got: " + toHour);
        }
    }

    /**
     * Creates a TimeOfDay factor for business hours (9am to 5pm).
     *
     * @return a new TimeOfDay factor for business hours
     */
    public static TimeOfDay businessHours() {
        return new TimeOfDay(9, 17);
    }

    /**
     * Creates a TimeOfDay factor for off-hours (5pm to 9am).
     *
     * <p>This range wraps across midnight.
     *
     * @return a new TimeOfDay factor for off-hours
     */
    public static TimeOfDay offHours() {
        return new TimeOfDay(17, 9);
    }

    @Override
    public String name() {
        return "time_of_day";
    }
}
