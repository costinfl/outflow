package dev.costinfl.outflow.insight;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The answer for one month (DESIGN: Home screen, blocks 1, 2 and 4). All amounts are positive minor units in
 * {@code currency}; percentages are whole numbers, rounded half up.
 */
public record MonthSummary(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03") String month,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long spentMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "How many of the previous 3 months have data; the average uses only those")
        int baselineMonths,
        @Schema(description = "Average spent over the baseline months; absent without history") Long averageSpentMinor,
        @Schema(description = "Spent vs. that average, e.g. 12 for +12%; absent without history or when it is 0")
        Integer deltaPct,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long incomeMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Income − spent; negative when spending more")
        long netMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Share of spending weighted by category confidence (before reviewedPct; kept for reference)")
        int accuracyPct,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Share of this month's spending with any category")
        int categorizedPct,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Spent without a category, wherever it ranks (it may be folded into the rest)")
        long uncategorizedMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int uncategorizedCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Top 5 by spent, largest first")
        List<CategorySpend> categories,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Everything below the top 5, folded")
        Rest rest,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Block 3: confirmed recurring payments")
        Committed committed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Months with data, oldest first, e.g. 2026-01")
        List<String> availableMonths,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "1, or 3 for the 'last 3 months' view: figures are totals over the months ending with `month`")
        int months,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "First month of the period (YYYY-MM)")
        String periodFrom,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Months of the period that have data: totals ÷ this = per-month averages")
        int monthsWithData,
        @Schema(description = "The plain-language insight line; absent when nothing moved notably") Insight insight,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "DESIGN's accuracy: share of the period's spending categorized and reviewed (Scope.REVIEWED)")
        int reviewedPct) {

    /**
     * "Restaurants are up 40% vs. your usual — 9 visits this month": a category whose per-month spending differs from
     * its usual by more than 25% and more than 100 currency units.
     */
    public record Insight(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long categoryId,
            String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "vs. usual, e.g. 40 or -59") int deltaPct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Per month minus usual; negative = less")
            long differenceMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Spent per month in the period") long perMonthMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long usualMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Transactions in the period (visits)")
            int transactionCount) {}

    /**
     * Committed every month: the monthly equivalents of the recurring payments active in the month (yearly ÷ 12). Equals
     * the Recurring screen's total for the same month.
     */
    public record Committed(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long monthlyMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count,
            @Schema(description = "Of the month's spent; absent when nothing was spent") Integer sharePct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Confirmed recurring income per month, as on the Recurring screen for this month")
            long incomeMonthlyMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Recurring income streams counted (a salary in two parts is two)")
            int incomeCount) {}

    /**
     * One category's spending. {@code categoryId} absent = uncategorized spending, which is ranked like a category so
     * unknown money never hides in the rest.
     */
    public record CategorySpend(
            Long categoryId,
            String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long spentMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Of the month's spent") int sharePct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Per-month average over the baseline months (the 3 before the period)")
            long usualMinor,
            @Schema(description = "Per month vs. usual; absent when there is no usual to compare with") Integer deltaPct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int transactionCount) {}

    public record Rest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long spentMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sharePct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int categoryCount) {}
}
