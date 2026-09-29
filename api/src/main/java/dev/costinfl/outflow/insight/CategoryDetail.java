package dev.costinfl.outflow.insight;

import dev.costinfl.outflow.category.Category;
import dev.costinfl.outflow.recurring.Cadence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One category over time (DESIGN: Detail screens, "what exactly is in this category?"). Amounts are positive minor
 * units in the category's own sense: money in for INCOME categories, money out otherwise. The month splits into its
 * recurring payments (charges of confirmed or ended ones, listed first) and variable spending (the merchants): the two
 * add up to {@code amountMinor}.
 */
public record CategoryDetail(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Category category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03") String month,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The 12 months ending with `month`, oldest first")
        List<MonthAmount> trend,
        @Schema(description = "Average over the trend months that have any data; absent when none has") Long averageMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "This month's variable spending by merchant (recurring charges excluded), largest first")
        List<MerchantAmount> merchants,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "This month's recurring charges, total")
        long recurringMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "This month's recurring payments, largest first")
        List<RecurringAmount> recurring) {

    /** A confirmed or ended recurring payment's charges in the category this month. */
    public record RecurringAmount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long subscriptionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence cadence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "CONFIRMED or ENDED") String state,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int transactionCount) {}

    public record MonthAmount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-03") String month,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether any transaction exists in this month")
            boolean hasData) {}

    public record MerchantAmount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long merchantId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int transactionCount) {}
}
