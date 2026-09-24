package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * "What am I committed to?" (DESIGN: Detail screens, Recurring payments). Totals are sums of the {@code counted}
 * items' own monthly and yearly equivalents, so the header always equals its rows.
 */
public record RecurringOverview(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
        @Schema(description = "The month viewed (YYYY-MM); absent = as of today") String month,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Committed per month: counted items only")
        long monthlyMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Committed per year: counted items only")
        long yearlyMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int countedCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Non-empty groups, subscriptions first")
        List<Group> groups,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Per account: how much history detection has")
        List<Coverage> coverage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Suggestions waiting in the review inbox")
        int suggestionCount) {

    public enum GroupKind { SUBSCRIPTIONS, BILLS }

    public enum Status { ACTIVE, ENDED }

    public record Group(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GroupKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Counted items only") long monthlyMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Highest monthly equivalent first")
            List<Item> items) {}

    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long merchantId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence cadence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AmountKind amountKind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expectedAmountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long monthlyMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long yearlyMinor,
            LocalDate nextExpectedDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Part of the totals: active (in the month viewed, when one is given)")
            boolean counted,
            @Schema(description = "Category of its latest charge") Long categoryId,
            String categoryName) {}

    public record Coverage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long accountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accountName,
            LocalDate from,
            LocalDate to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Calendar months from first to last date")
            int months,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "3+ months: monthly payments detectable")
            boolean monthly,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "13+ months: yearly payments detectable")
            boolean yearly) {}
}
