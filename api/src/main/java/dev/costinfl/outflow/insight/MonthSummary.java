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
                description = "Share of this month's spending to trust: weighted by category confidence")
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Months with data, oldest first, e.g. 2026-01")
        List<String> availableMonths) {

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
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Average over the baseline months")
            long usualMinor,
            @Schema(description = "vs. usual; absent when there is no usual to compare with") Integer deltaPct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int transactionCount) {}

    public record Rest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long spentMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sharePct,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int categoryCount) {}
}
