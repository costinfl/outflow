package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * "What am I committed to?" (DESIGN: Detail screens, Recurring payments). Totals are sums of the {@code counted}
 * items' own monthly and yearly equivalents, so the header always equals its rows. Recurring income is its own group
 * and never part of the committed totals.
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
        int suggestionCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Recurring income per month: the INCOME group's counted items (a salary in two parts is two)")
        long incomeMonthlyMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Recurring transfers to own accounts or savings: shown, never part of the totals")
        List<StandingTransfer> standingTransfers,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Standing transfers per month: active ones only")
        long standingMonthlyMinor) {

    /** Payments (subscriptions, bills) are commitments; INCOME is money expected in. */
    public enum GroupKind { SUBSCRIPTIONS, BILLS, INCOME }

    /** DESIGN's status chip: Active, Price changed, Missed, Ended (an open alert decides the middle two). */
    public enum Status { ACTIVE, PRICE_CHANGED, MISSED, ENDED }

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
            String categoryName,
            @Schema(description = "Remind this many days before the next charge; absent = no reminder")
            Integer remindDaysBefore) {}

    /**
     * A recurring transfer out (DESIGN: "Standing transfers (savings, own accounts — shown but excluded from the
     * total)"). Detected from the transactions each time, never stored and never a question.
     */
    public record StandingTransfer(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long accountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long merchantId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(description = "The own account it goes to, when the transfer is paired") String toAccountName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence cadence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AmountKind amountKind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expectedAmountMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long monthlyMinor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int occurrences,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate lastDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate nextExpectedDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "false once the next transfer is overdue (the transfer stopped)")
            boolean active) {}

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
