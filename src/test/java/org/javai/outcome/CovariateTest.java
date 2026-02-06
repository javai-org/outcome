package org.javai.outcome;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Set;

import static java.time.DayOfWeek.*;
import static org.assertj.core.api.Assertions.*;

class CovariateTest {

    // === DaysOfWeek Tests ===

    @Test
    void daysOfWeek_of_createsCovariate() {
        DaysOfWeek covariate = DaysOfWeek.of(MONDAY, WEDNESDAY, FRIDAY);

        assertThat(covariate.days()).containsExactlyInAnyOrder(MONDAY, WEDNESDAY, FRIDAY);
        assertThat(covariate.name()).isEqualTo("days_of_week");
    }

    @Test
    void daysOfWeek_weekdays_containsMonThroughFri() {
        DaysOfWeek covariate = DaysOfWeek.weekdays();

        assertThat(covariate.days()).containsExactlyInAnyOrder(
                MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY
        );
    }

    @Test
    void daysOfWeek_weekends_containsSatAndSun() {
        DaysOfWeek covariate = DaysOfWeek.weekends();

        assertThat(covariate.days()).containsExactlyInAnyOrder(SATURDAY, SUNDAY);
    }

    @Test
    void daysOfWeek_singleDay_works() {
        DaysOfWeek covariate = DaysOfWeek.of(MONDAY);

        assertThat(covariate.days()).containsExactly(MONDAY);
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
        DaysOfWeek covariate = DaysOfWeek.of(MONDAY, TUESDAY);

        assertThatThrownBy(() -> covariate.days().add(WEDNESDAY))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // === TimeOfDay Tests ===

    @Test
    void timeOfDay_businessHours_is9to17() {
        TimeOfDay covariate = TimeOfDay.businessHours();

        assertThat(covariate.fromHour()).isEqualTo(9);
        assertThat(covariate.toHour()).isEqualTo(17);
        assertThat(covariate.name()).isEqualTo("time_of_day");
    }

    @Test
    void timeOfDay_offHours_is17to9() {
        TimeOfDay covariate = TimeOfDay.offHours();

        assertThat(covariate.fromHour()).isEqualTo(17);
        assertThat(covariate.toHour()).isEqualTo(9);
    }

    @Test
    void timeOfDay_customRange_works() {
        TimeOfDay covariate = new TimeOfDay(8, 20);

        assertThat(covariate.fromHour()).isEqualTo(8);
        assertThat(covariate.toHour()).isEqualTo(20);
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
        Region covariate = new Region("us-east-1");

        assertThat(covariate.value()).isEqualTo("us-east-1");
        assertThat(covariate.name()).isEqualTo("region");
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

    // === CustomCovariate Tests ===

    @Test
    void customCovariate_createsWithNameAndValue() {
        CustomCovariate covariate = new CustomCovariate("customer_tier", "premium");

        assertThat(covariate.name()).isEqualTo("customer_tier");
        assertThat(covariate.value()).isEqualTo("premium");
    }

    @Test
    void customCovariate_rejectsNullName() {
        assertThatThrownBy(() -> new CustomCovariate(null, "value"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name must not be null");
    }

    @Test
    void customCovariate_rejectsNullValue() {
        assertThatThrownBy(() -> new CustomCovariate("name", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    void customCovariate_rejectsBlankName() {
        assertThatThrownBy(() -> new CustomCovariate("", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");

        assertThatThrownBy(() -> new CustomCovariate("   ", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");
    }

    @Test
    void customCovariate_allowsEmptyValue() {
        // Empty value is allowed (blank name is not)
        CustomCovariate covariate = new CustomCovariate("feature_flag", "");
        assertThat(covariate.value()).isEmpty();
    }

    // === Covariate Sealed Interface Tests ===

    @Test
    void covariate_allImplementationsArePermitted() {
        Covariate daysOfWeek = DaysOfWeek.weekdays();
        Covariate timeOfDay = TimeOfDay.businessHours();
        Covariate region = new Region("us-east-1");
        Covariate custom = new CustomCovariate("tier", "premium");

        assertThat(daysOfWeek).isInstanceOf(Covariate.class);
        assertThat(timeOfDay).isInstanceOf(Covariate.class);
        assertThat(region).isInstanceOf(Covariate.class);
        assertThat(custom).isInstanceOf(Covariate.class);
    }

    @Test
    void covariate_canBeUsedInPatternMatching() {
        Covariate covariate = DaysOfWeek.weekdays();

        String result = switch (covariate) {
            case DaysOfWeek d -> "days: " + d.days().size();
            case TimeOfDay t -> "time: " + t.fromHour() + "-" + t.toHour();
            case Region r -> "region: " + r.value();
            case CustomCovariate c -> "custom: " + c.name() + "=" + c.value();
        };

        assertThat(result).isEqualTo("days: 5");
    }
}
