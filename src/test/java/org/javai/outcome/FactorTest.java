package org.javai.outcome;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Set;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.*;

class FactorTest {

    // === DaysOfWeek Tests ===

    @Test
    void daysOfWeek_of_createsFactor() {
        DaysOfWeek factor = DaysOfWeek.of(MONDAY, WEDNESDAY, FRIDAY);

        assertThat(factor.days()).containsExactlyInAnyOrder(MONDAY, WEDNESDAY, FRIDAY);
        assertThat(factor.name()).isEqualTo("days_of_week");
    }

    @Test
    void daysOfWeek_weekdays_containsMonThroughFri() {
        DaysOfWeek factor = DaysOfWeek.weekdays();

        assertThat(factor.days()).containsExactlyInAnyOrder(
                MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY
        );
    }

    @Test
    void daysOfWeek_weekends_containsSatAndSun() {
        DaysOfWeek factor = DaysOfWeek.weekends();

        assertThat(factor.days()).containsExactlyInAnyOrder(SATURDAY, SUNDAY);
    }

    @Test
    void daysOfWeek_singleDay_works() {
        DaysOfWeek factor = DaysOfWeek.of(MONDAY);

        assertThat(factor.days()).containsExactly(MONDAY);
    }

    @Test
    void daysOfWeek_rejectsNullDays() {
        assertThatThrownBy(() -> new DaysOfWeek(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("days must not be null");
    }

    @Test
    void daysOfWeek_rejectsEmptyDays() {
        assertThatThrownBy(() -> new DaysOfWeek(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days must not be empty");
    }

    @Test
    void daysOfWeek_of_rejectsEmptyVarargs() {
        assertThatThrownBy(() -> DaysOfWeek.of())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days must not be empty");
    }

    @Test
    void daysOfWeek_daysAreImmutable() {
        DaysOfWeek factor = DaysOfWeek.of(MONDAY, TUESDAY);

        assertThatThrownBy(() -> factor.days().add(WEDNESDAY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // === TimeOfDay Tests ===

    @Test
    void timeOfDay_businessHours_is9to17() {
        TimeOfDay factor = TimeOfDay.businessHours();

        assertThat(factor.fromHour()).isEqualTo(9);
        assertThat(factor.toHour()).isEqualTo(17);
        assertThat(factor.name()).isEqualTo("time_of_day");
    }

    @Test
    void timeOfDay_offHours_is17to9() {
        TimeOfDay factor = TimeOfDay.offHours();

        assertThat(factor.fromHour()).isEqualTo(17);
        assertThat(factor.toHour()).isEqualTo(9);
    }

    @Test
    void timeOfDay_customRange_works() {
        TimeOfDay factor = new TimeOfDay(8, 20);

        assertThat(factor.fromHour()).isEqualTo(8);
        assertThat(factor.toHour()).isEqualTo(20);
    }

    @Test
    void timeOfDay_rejectsInvalidFromHour() {
        assertThatThrownBy(() -> new TimeOfDay(-1, 17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromHour must be between 0 and 23");

        assertThatThrownBy(() -> new TimeOfDay(24, 17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromHour must be between 0 and 23");
    }

    @Test
    void timeOfDay_rejectsInvalidToHour() {
        assertThatThrownBy(() -> new TimeOfDay(9, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toHour must be between 0 and 23");

        assertThatThrownBy(() -> new TimeOfDay(9, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toHour must be between 0 and 23");
    }

    @Test
    void timeOfDay_allowsBoundaryValues() {
        assertThatCode(() -> new TimeOfDay(0, 23)).doesNotThrowAnyException();
        assertThatCode(() -> new TimeOfDay(23, 0)).doesNotThrowAnyException();
    }

    // === Region Tests ===

    @Test
    void region_createsWithValue() {
        Region factor = new Region("us-east-1");

        assertThat(factor.value()).isEqualTo("us-east-1");
        assertThat(factor.name()).isEqualTo("region");
    }

    @Test
    void region_rejectsNullValue() {
        assertThatThrownBy(() -> new Region(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    void region_rejectsBlankValue() {
        assertThatThrownBy(() -> new Region(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value must not be blank");

        assertThatThrownBy(() -> new Region("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value must not be blank");
    }

    // === CustomFactor Tests ===

    @Test
    void customFactor_createsWithNameAndValue() {
        CustomFactor factor = new CustomFactor("customer_tier", "premium");

        assertThat(factor.name()).isEqualTo("customer_tier");
        assertThat(factor.value()).isEqualTo("premium");
    }

    @Test
    void customFactor_rejectsNullName() {
        assertThatThrownBy(() -> new CustomFactor(null, "value"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name must not be null");
    }

    @Test
    void customFactor_rejectsNullValue() {
        assertThatThrownBy(() -> new CustomFactor("name", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    void customFactor_rejectsBlankName() {
        assertThatThrownBy(() -> new CustomFactor("", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");

        assertThatThrownBy(() -> new CustomFactor("   ", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");
    }

    @Test
    void customFactor_allowsEmptyValue() {
        // Empty value is allowed (blank name is not)
        CustomFactor factor = new CustomFactor("feature_flag", "");
        assertThat(factor.value()).isEmpty();
    }

    // === Factor Sealed Interface Tests ===

    @Test
    void factor_allImplementationsArePermitted() {
        Factor daysOfWeek = DaysOfWeek.weekdays();
        Factor timeOfDay = TimeOfDay.businessHours();
        Factor region = new Region("us-east-1");
        Factor custom = new CustomFactor("tier", "premium");

        assertThat(daysOfWeek).isInstanceOf(Factor.class);
        assertThat(timeOfDay).isInstanceOf(Factor.class);
        assertThat(region).isInstanceOf(Factor.class);
        assertThat(custom).isInstanceOf(Factor.class);
    }

    @Test
    void factor_canBeUsedInPatternMatching() {
        Factor factor = DaysOfWeek.weekdays();

        String result = switch (factor) {
            case DaysOfWeek d -> "days: " + d.days().size();
            case TimeOfDay t -> "time: " + t.fromHour() + "-" + t.toHour();
            case Region r -> "region: " + r.value();
            case CustomFactor c -> "custom: " + c.name() + "=" + c.value();
        };

        assertThat(result).isEqualTo("days: 5");
    }
}
