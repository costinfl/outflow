package dev.costinfl.outflow.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.insight.MonthSummary.CategorySpend;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CP6.3: when the insight line appears (DESIGN: "only when a delta is notable (> 25% and > 100 RON)"). */
class InsightLineTest {

    /** A category row as InsightService ranks it: spent over the period, usual per month, delta per month. */
    static CategorySpend row(long id, String name, long spent, long usual, int divisor) {
        long perMonth = InsightService.divide(spent, divisor);
        Integer delta = usual > 0 ? InsightService.deltaPct(perMonth, usual) : null;
        return new CategorySpend(id == 0 ? null : id, name.toUpperCase(), name, spent, 0, usual, delta, 9);
    }

    static java.util.Optional<MonthSummary.Insight> insight(int divisor, CategorySpend... rows) {
        return InsightService.insight(List.of(rows), divisor, "RON");
    }

    @Test
    void notableRiseIsTheLine() {
        var i = insight(1, row(2, "Restaurants", 70_000, 50_000, 1)).orElseThrow();

        assertThat(i.name()).isEqualTo("Restaurants");
        assertThat(i.deltaPct()).isEqualTo(40);
        assertThat(i.differenceMinor()).isEqualTo(20_000);
        assertThat(i.perMonthMinor()).isEqualTo(70_000);
        assertThat(i.usualMinor()).isEqualTo(50_000);
        assertThat(i.transactionCount()).isEqualTo(9);
    }

    @Test
    void bothThresholdsMustBeExceeded() {
        assertThat(insight(1, row(2, "Restaurants", 14_000, 10_000, 1))).isEmpty(); // +40% but 40 RON
        assertThat(insight(1, row(2, "Restaurants", 30_000, 20_000, 1))).isEmpty(); // +50% but exactly 100 RON
        assertThat(insight(1, row(2, "Restaurants", 30_001, 20_000, 1))).isPresent(); // just over 100 RON
        assertThat(insight(1, row(1, "Groceries", 125_000, 100_000, 1))).isEmpty(); // exactly 25%
        assertThat(insight(1, row(1, "Groceries", 125_500, 100_000, 1))).isPresent(); // 26% (rounded), 255 RON
    }

    @Test
    void theBiggestMoveWinsAndUncategorizedNeverDoes() {
        var i = insight(1,
                row(0, "Uncategorized", 900_000, 10_000, 1),
                row(1, "Groceries", 45_000, 110_000, 1), // −59%, −650 RON
                row(2, "Restaurants", 70_000, 50_000, 1)) // +40%, +200 RON
                .orElseThrow();

        assertThat(i.name()).isEqualTo("Groceries");
        assertThat(i.deltaPct()).isEqualTo(-59);
        assertThat(i.differenceMinor()).isEqualTo(-65_000);
    }

    @Test
    void aNewCategoryHasNoUsualAndNoLine() {
        assertThat(insight(1, row(4, "Fuel", 250_000, 0, 1))).isEmpty();
    }

    @Test
    void threeMonthsCompareTheirPerMonthAverage() {
        // 900 RON over 3 months = 300 a month vs a usual 100: +200%, +200 RON a month.
        var i = insight(3, row(2, "Restaurants", 90_000, 10_000, 3)).orElseThrow();

        assertThat(i.perMonthMinor()).isEqualTo(30_000);
        assertThat(i.deltaPct()).isEqualTo(200);
        assertThat(i.differenceMinor()).isEqualTo(20_000);
    }
}
