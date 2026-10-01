package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.Candidate.Strength;
import dev.costinfl.outflow.recurring.RecurrenceDetector.Group;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CP4.1: grouping into amount bands, monthly and yearly cadence fit, scoring. Expected values computed by hand. */
class RecurrenceDetectorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 7, 20);

    final RecurrenceDetector detector = new RecurrenceDetector();
    long nextId = 1;

    /** Charges as "yyyy-mm-dd:amountMinor". */
    List<Candidate> detect(LocalDate today, String... charges) {
        var occurrences = new ArrayList<Occurrence>();
        for (String c : charges) {
            String[] parts = c.split(":");
            occurrences.add(new Occurrence(nextId++, LocalDate.parse(parts[0]), Long.parseLong(parts[1])));
        }
        return detector.detect(new Group(1, 2, "RON", occurrences), today);
    }

    Candidate only(List<Candidate> found) {
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    /** CP6.11: every other Friday (DESIGN: bi-weekly, 14 days ± 2, at least 3). */
    @Test
    void everyOtherFridayIsBiWeekly() {
        var c = only(detect(LocalDate.of(2026, 4, 2), "2026-01-02:4500", "2026-01-16:4500", "2026-01-30:4500",
                "2026-02-13:4500", "2026-02-27:4500", "2026-03-13:4500", "2026-03-27:4500"));

        assertThat(c.cadence()).isEqualTo(Cadence.BIWEEKLY);
        assertThat(c.anchorDay()).isEqualTo(5); // Friday
        assertThat(c.anchorMonth()).isEqualTo(1); // the even weeks since the epoch
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(c.score()).isEqualTo(new Candidate.Score(1, 1, 1, 1));
        assertThat(c.transactionIds()).hasSize(7);
    }

    @Test
    void aBiWeeklyChargeTwoDaysLateIsOnTime() {
        var c = only(detect(LocalDate.of(2026, 4, 2), "2026-01-02:4500", "2026-01-16:4500", "2026-02-01:4500",
                "2026-02-13:4500", "2026-02-27:4500", "2026-03-13:4500", "2026-03-27:4500"));

        assertThat(c.cadence()).isEqualTo(Cadence.BIWEEKLY);
        assertThat(c.score().interval()).isEqualTo(1); // 1 Feb is a Sunday, 2 days after the 30 Jan Friday
    }

    /** CP6.11: every third month on the 15th (DESIGN: quarterly, ± 5 days, at least 3). */
    @Test
    void everyThirdMonthIsQuarterly() {
        var c = only(detect(LocalDate.of(2026, 8, 1), "2025-07-15:29999", "2025-10-15:29999", "2026-01-15:29999",
                "2026-04-15:29999", "2026-07-15:29999"));

        assertThat(c.cadence()).isEqualTo(Cadence.QUARTERLY);
        assertThat(c.anchorDay()).isEqualTo(15);
        assertThat(c.anchorMonth()).isEqualTo(1); // January, April, July, October
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(c.amountKind()).isEqualTo(AmountKind.FIXED);
        assertThat(c.transactionIds()).hasSize(5);
    }

    @Test
    void quarterlyChargesMayMoveFiveDays() {
        var c = only(detect(LocalDate.of(2026, 8, 10), "2025-11-03:12000", "2026-02-02:12000", "2026-05-08:12000",
                "2026-08-03:12000"));

        assertThat(c.cadence()).isEqualTo(Cadence.QUARTERLY);
        assertThat(c.anchorDay()).isEqualTo(3);
        assertThat(c.anchorMonth()).isEqualTo(2); // February, May, August, November
        assertThat(c.score().interval()).isEqualTo(1); // 8 May is 5 days after the 3rd
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void aShopVisitedAboutEveryThreeMonthsOnRandomDaysIsNotQuarterly() {
        assertThat(detect(LocalDate.of(2026, 2, 1), "2025-01-05:20000", "2025-04-20:20000", "2025-07-02:20000",
                "2025-10-28:20000", "2026-01-11:20000")).isEmpty();
    }

    @Test
    void weeklyAndMonthlyStayWhatTheyAre() {
        assertThat(only(detect(LocalDate.of(2026, 3, 2), "2026-02-02:1000", "2026-02-09:1000", "2026-02-16:1000",
                "2026-02-23:1000", "2026-03-02:1000")).cadence()).isEqualTo(Cadence.WEEKLY);
        assertThat(only(detect(LocalDate.of(2026, 6, 20), "2026-01-15:4999", "2026-02-15:4999", "2026-03-15:4999",
                "2026-04-15:4999", "2026-05-15:4999", "2026-06-15:4999")).cadence()).isEqualTo(Cadence.MONTHLY);
    }

    /** CP6.5: a salary in two parts, around the 25th (advance) and the 10th, is two monthly streams. */
    @Test
    void twoPaymentsAMonthOnTwoDaysAreTwoMonthlyStreams() {
        var found = detect(TODAY,
                "2026-01-09:849000", "2026-01-26:872100", "2026-02-10:872100", "2026-02-25:872100",
                "2026-03-10:872100", "2026-03-25:872100", "2026-04-09:872100", "2026-04-24:872100",
                "2026-05-11:960300", "2026-05-25:950000", "2026-06-10:928700", "2026-06-25:950000",
                "2026-07-10:928500");

        assertThat(found).hasSize(2);
        assertThat(found).extracting(Candidate::cadence).containsOnly(Cadence.MONTHLY);
        assertThat(found).extracting(Candidate::anchorDay).containsExactlyInAnyOrder(10, 25);
        var tenth = found.stream().filter(c -> c.anchorDay() == 10).findFirst().orElseThrow();
        var advance = found.stream().filter(c -> c.anchorDay() == 25).findFirst().orElseThrow();
        assertThat(tenth.transactionIds()).containsExactly(1L, 3L, 5L, 7L, 9L, 11L, 13L);
        assertThat(advance.transactionIds()).containsExactly(2L, 4L, 6L, 8L, 10L, 12L);
        assertThat(tenth.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(advance.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 7, 25));
        assertThat(found).allSatisfy(c -> assertThat(c.strength()).isEqualTo(Strength.PROPOSED));
    }

    @Test
    void twoPaymentsAMonthOnScatteredDaysAreNoStream() {
        assertThat(detect(TODAY,
                "2026-01-03:10000", "2026-01-17:10000", "2026-02-08:10000", "2026-02-21:10000",
                "2026-03-01:10000", "2026-03-14:10000", "2026-04-11:10000", "2026-04-28:10000",
                "2026-05-05:10000", "2026-05-19:10000", "2026-06-02:10000", "2026-06-23:10000")).isEmpty();
    }

    @Test
    void fixedMonthlyWithWeekendShifts() {
        // 15 Feb and 15 Mar 2026 are Sundays: charged the Monday after.
        var c = only(detect(TODAY, "2026-01-15:4999", "2026-02-16:4999", "2026-03-16:4999", "2026-04-15:4999",
                "2026-05-15:4999", "2026-06-15:4999"));

        assertThat(c.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(c.anchorDay()).isEqualTo(15);
        assertThat(c.anchorMonth()).isNull();
        assertThat(c.amountKind()).isEqualTo(AmountKind.FIXED);
        assertThat(c.expectedAmountMinor()).isEqualTo(4999);
        assertThat(c.toleranceMinor()).isZero();
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(c.score()).isEqualTo(new Candidate.Score(1, 1, 1, 1));
        assertThat(c.strength()).isEqualTo(Strength.PROPOSED);
        assertThat(c.transactionIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void variableMonthly() {
        var c = only(detect(TODAY, "2026-01-08:21001", "2026-02-09:18550", "2026-03-08:19990", "2026-04-10:23400",
                "2026-05-08:20500", "2026-06-09:22010"));

        assertThat(c.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(c.anchorDay()).isEqualTo(8);
        assertThat(c.amountKind()).isEqualTo(AmountKind.VARIABLE);
        // Median of the last three (23400, 20500, 22010); tolerance 2 × MAD around the median 20750 = 2 × 1010.
        assertThat(c.expectedAmountMinor()).isEqualTo(22010);
        assertThat(c.toleranceMinor()).isEqualTo(2020);
        assertThat(c.bandMinMinor()).isEqualTo(18550);
        assertThat(c.bandMaxMinor()).isEqualTo(23400);
        assertThat(c.score().interval()).isEqualTo(1);
        assertThat(c.score().amount()).isCloseTo(1 - 0.07306, within(0.0001)); // CV = 1527.66 / 20908.5
        assertThat(c.strength()).isEqualTo(Strength.PROPOSED);
    }

    @Test
    void monthEndAnchorClampsToShortMonths() {
        // Anchor 31: 30 Nov, 30 Apr; February's 28th is a Saturday, charged Monday 2 March and still February's.
        var c = only(detect(TODAY, "2025-10-31:6500", "2025-11-30:6500", "2025-12-31:6500", "2026-01-31:6500",
                "2026-03-02:6500", "2026-03-31:6500", "2026-04-30:6500"));

        assertThat(c.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(c.anchorDay()).isEqualTo(31);
        assertThat(c.score().interval()).isEqualTo(1);
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    void oneMissedMonthCostsLittle() {
        var c = only(detect(TODAY, "2026-01-03:25000", "2026-02-03:25000", "2026-03-03:25000", "2026-05-04:25000",
                "2026-06-03:25000", "2026-07-03:25000"));

        assertThat(c.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(c.score().interval()).isEqualTo(0.8); // 4 of 5 gaps are one month on anchor
        assertThat(c.confidence()).isCloseTo(0.92, within(1e-9));
        assertThat(c.strength()).isEqualTo(Strength.PROPOSED);
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @Test
    void weekendAnchorCountsFromTheNextBusinessDay() {
        // 7 Feb and 7 Mar 2026 are Saturdays: the 12th is 5 days after the anchor but 3 after Monday the 9th.
        var c = only(detect(TODAY, "2026-01-07:3000", "2026-02-12:3000", "2026-03-12:3000", "2026-04-07:3000",
                "2026-05-07:3000"));

        assertThat(c.anchorDay()).isEqualTo(7);
        assertThat(c.score().interval()).isEqualTo(1);
    }

    @Test
    void yearly() {
        var c = only(detect(TODAY, "2024-03-10:5500", "2025-03-12:5500", "2026-03-09:5500"));

        assertThat(c.cadence()).isEqualTo(Cadence.YEARLY);
        assertThat(c.anchorMonth()).isEqualTo(3);
        assertThat(c.anchorDay()).isEqualTo(10);
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2027, 3, 10));
        assertThat(c.score()).isEqualTo(new Candidate.Score(1, 1, 0.75, 1));
        assertThat(c.strength()).isEqualTo(Strength.PROPOSED);
    }

    @Test
    void yearlyAcrossNewYear() {
        var c = only(detect(TODAY, "2024-12-30:12000", "2026-01-02:12000"));

        assertThat(c.cadence()).isEqualTo(Cadence.YEARLY);
        assertThat(c.anchorMonth()).isEqualTo(12);
        assertThat(c.anchorDay()).isEqualTo(30);
        assertThat(c.score().interval()).isEqualTo(1); // 2 Jan is 3 days after 30 Dec 2025
    }

    @Test
    void amountBandsSeparateAPlanFromOneOffPurchasesAtTheSameMerchant() {
        var found = detect(TODAY, "2026-01-05:2999", "2026-01-19:12000", "2026-02-05:2999", "2026-03-05:2999",
                "2026-03-22:45000", "2026-04-06:2999", "2026-05-05:2999", "2026-05-11:8999", "2026-06-05:2999");

        var c = only(found);
        assertThat(c.bandMaxMinor()).isEqualTo(2999);
        assertThat(c.transactionIds()).containsExactly(1L, 3L, 4L, 6L, 7L, 9L);
    }

    @Test
    void bandsSplitAboveTwentyFivePercent() {
        assertThat(AmountBands.gapAbove25Pct(10000, 12500)).isFalse();
        assertThat(AmountBands.gapAbove25Pct(10000, 12501)).isTrue();
        var bands = AmountBands.split(List.of(new Occurrence(1, LocalDate.of(2026, 1, 1), 100),
                new Occurrence(2, LocalDate.of(2026, 1, 2), 125), new Occurrence(3, LocalDate.of(2026, 1, 3), 160)));
        assertThat(bands).extracting(b -> b.stream().map(Occurrence::transactionId).toList())
                .containsExactly(List.of(1L, 2L), List.of(3L));
    }

    @Test
    void irregularSpendingIsNotRecurring() {
        assertThat(detect(TODAY, "2026-01-02:15000", "2026-01-09:16000", "2026-01-20:15500", "2026-02-03:17000",
                "2026-02-17:16500", "2026-03-01:15800")).isEmpty();
    }

    @Test
    void tooFewOccurrences() {
        assertThat(detect(TODAY, "2026-05-15:4999", "2026-06-15:4999")).isEmpty();
    }

    @Test
    void recencyDecaysFromOneAndAHalfToThreeSteps() {
        assertThat(RecurrenceDetector.recency(1.5)).isEqualTo(1);
        assertThat(RecurrenceDetector.recency(2.25)).isEqualTo(0.5);
        assertThat(RecurrenceDetector.recency(3)).isZero();

        var stopped = only(detect(LocalDate.of(2026, 10, 15), "2026-01-15:4999", "2026-02-16:4999",
                "2026-03-16:4999", "2026-04-15:4999", "2026-05-15:4999", "2026-06-15:4999"));
        assertThat(stopped.score().recency()).isZero();
        assertThat(stopped.confidence()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void weakFitIsOnlyPossible() {
        // One charge a week off anchor, and nothing for three months: 0.4/3 + 0.2 + 0.2 × 4/6 + 0 = 0.467.
        var c = only(detect(TODAY, "2026-01-05:1500", "2026-02-05:1500", "2026-03-12:1500", "2026-04-06:1500"));

        assertThat(c.confidence()).isCloseTo(0.4667, within(0.0001));
        assertThat(c.strength()).isEqualTo(Strength.POSSIBLE);
    }

    @Test
    void belowPossibleIsDropped() {
        // Anchor 5; the 12th is off anchor twice, so no gap is regular: 0 + 0.2 + 0.2 × 4/6 + 0 = 0.333.
        assertThat(detect(TODAY, "2026-01-05:1500", "2026-02-12:1500", "2026-03-05:1500", "2026-04-12:1500")).isEmpty();
    }

    @Test
    void weeklyAnchoredToAWeekday() {
        // Mondays from 5 January 2026; one charge a day late (Tuesday 20 January).
        var c = only(detect(LocalDate.of(2026, 2, 16), "2026-01-05:1500", "2026-01-12:1500", "2026-01-20:1500",
                "2026-01-26:1500", "2026-02-02:1500", "2026-02-09:1500"));

        assertThat(c.cadence()).isEqualTo(Cadence.WEEKLY);
        assertThat(c.anchorDay()).isEqualTo(1); // Monday
        assertThat(c.anchorMonth()).isNull();
        assertThat(c.score().interval()).isEqualTo(1);
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(c.strength()).isEqualTo(Strength.PROPOSED);
    }

    @Test
    void weeklyNeedsFourCharges() {
        assertThat(detect(LocalDate.of(2026, 1, 27), "2026-01-05:1500", "2026-01-12:1500", "2026-01-19:1500")).isEmpty();
    }

    @Test
    void dailyOnWeekdaysTreatsWeekendsAsNoGap() {
        var dates = new java.util.ArrayList<String>();
        for (LocalDate d = LocalDate.of(2026, 3, 2); d.isBefore(LocalDate.of(2026, 3, 21)); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                dates.add(d + ":800");
            }
        }
        var c = only(detect(LocalDate.of(2026, 3, 21), dates.toArray(String[]::new)));

        assertThat(c.cadence()).isEqualTo(Cadence.DAILY);
        assertThat(c.anchorDay()).isNull();
        assertThat(c.occurrences()).isEqualTo(15);
        assertThat(c.score().interval()).isEqualTo(1);
        assertThat(c.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 3, 21));
    }

    @Test
    void aMissedWeekdayIsAnIrregularDailyGap() {
        var c = only(detect(LocalDate.of(2026, 3, 17), "2026-03-02:800", "2026-03-03:800", "2026-03-04:800",
                "2026-03-05:800", "2026-03-06:800", "2026-03-09:800", "2026-03-10:800", "2026-03-12:800",
                "2026-03-13:800", "2026-03-16:800"));

        assertThat(c.cadence()).isEqualTo(Cadence.DAILY);
        assertThat(c.score().interval()).isCloseTo(8 / 9.0, within(1e-9)); // Tue 10 → Thu 12 skips a weekday
    }

    /** A 9.99 plan on the 5th and occasional purchases of 11–12 at the same merchant: within 25%, one band. */
    static final String[] PLAN_AND_PURCHASES = {"2026-01-05:999", "2026-02-05:999", "2026-03-05:999", "2026-04-05:999",
            "2026-05-05:999", "2026-06-05:999", "2026-02-20:1150", "2026-04-18:1100", "2026-06-11:1200"};

    /** CP6.16: without a cut, the purchases muddle the plan into one variable stream with a poor rhythm. */
    @Test
    void aPlanMixedWithPurchasesIsOneMuddledStream() {
        var c = only(detect(LocalDate.of(2026, 7, 1), PLAN_AND_PURCHASES));

        assertThat(c.amountKind()).isEqualTo(AmountKind.VARIABLE);
        assertThat(c.transactionIds()).hasSize(9);
        assertThat(c.bandMaxMinor()).isEqualTo(1200);
    }

    /** CP6.16: cut at 10.50, the plan is a clean fixed monthly stream and the purchases are no stream at all. */
    @Test
    void aUserCutSeparatesThePlanFromThePurchases() {
        var occurrences = new ArrayList<Occurrence>();
        for (String c : PLAN_AND_PURCHASES) {
            String[] parts = c.split(":");
            occurrences.add(new Occurrence(nextId++, LocalDate.parse(parts[0]), Long.parseLong(parts[1])));
        }
        var found = detector.detect(new Group(1, 2, "RON", occurrences,
                dev.costinfl.outflow.category.CategoryRule.Direction.OUT, List.of(1050L)), LocalDate.of(2026, 7, 1));

        var c = only(found);
        assertThat(c.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(c.amountKind()).isEqualTo(AmountKind.FIXED);
        assertThat(c.expectedAmountMinor()).isEqualTo(999);
        assertThat(c.transactionIds()).hasSize(6);
        assertThat(c.anchorDay()).isEqualTo(5);
    }

    /** A cut splits bands between amounts, never inside equal ones; below the cut and at-or-above it are apart. */
    @Test
    void cutsSplitBandsAtTheirAmount() {
        var o = List.of(new Occurrence(1, LocalDate.of(2026, 1, 1), 999), new Occurrence(2, LocalDate.of(2026, 1, 2), 1050),
                new Occurrence(3, LocalDate.of(2026, 1, 3), 1199));
        assertThat(AmountBands.split(o)).hasSize(1);
        assertThat(AmountBands.split(o, List.of(1050L))).extracting(List::size).containsExactly(1, 2);
        assertThat(AmountBands.split(o, List.of(1051L))).extracting(List::size).containsExactly(2, 1);
        assertThat(AmountBands.split(o, List.of(500L, 5000L))).hasSize(1);
    }
}
