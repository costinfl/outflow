package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CP4.4: monthly and yearly equivalents, in whole minor units (DESIGN: yearly ÷ 12). */
class CadenceTest {

    @Test
    void monthlyEquivalent() {
        assertThat(Cadence.MONTHLY.monthlyMinor(4999)).isEqualTo(4999);
        assertThat(Cadence.YEARLY.monthlyMinor(5500)).isEqualTo(458); // 458.33
        assertThat(Cadence.YEARLY.monthlyMinor(6)).isEqualTo(1); // 0.5 rounds half up
        assertThat(Cadence.YEARLY.monthlyMinor(5)).isZero();
        assertThat(Cadence.YEARLY.monthlyMinor(12000)).isEqualTo(1000);
    }

    @Test
    void yearlyEquivalent() {
        assertThat(Cadence.MONTHLY.yearlyMinor(4999)).isEqualTo(59_988);
        assertThat(Cadence.YEARLY.yearlyMinor(5500)).isEqualTo(5500);
    }

    @Test
    void weeklyAndDailyEquivalents() {
        assertThat(Cadence.WEEKLY.monthlyMinor(1000)).isEqualTo(4330); // × 4.33
        assertThat(Cadence.WEEKLY.monthlyMinor(1)).isEqualTo(4); // 4.33 rounds to 4
        assertThat(Cadence.WEEKLY.yearlyMinor(1000)).isEqualTo(52_000);
        assertThat(Cadence.DAILY.monthlyMinor(1000)).isEqualTo(30_420); // × 30.42
        assertThat(Cadence.DAILY.yearlyMinor(1000)).isEqualTo(365_000);
    }
}
