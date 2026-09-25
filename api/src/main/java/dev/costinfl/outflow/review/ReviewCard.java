package dev.costinfl.outflow.review;

import dev.costinfl.outflow.recurring.Cadence;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One question for the user (DESIGN: Review inbox). One card = one merchant, never one transaction (a possible duplicate
 * is about two). Subscription fields are set on SUBSCRIPTION cards, {@code transactionCount} and the sent / received
 * split on UNCATEGORIZED_MERCHANT cards (a person can be both paid and paying back), the pending/posted fields on POSSIBLE_DUPLICATE cards, the alert fields on PRICE_CHANGE and MISSED_CHARGE
 * cards (which also carry the subscription's id, cadence and expected amount).
 */
public record ReviewCard(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Stable id, used to skip the card",
                example = "subscription:12") String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Kind kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Money the answer affects (positive minor units); cards are sorted by it, largest first")
        long affectedMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long merchantId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Subscription name or merchant display name")
        String name,
        Long subscriptionId,
        Cadence cadence,
        Long expectedAmountMinor,
        AmountKind amountKind,
        @Schema(description = "First charge of the subscription") LocalDate since,
        @Schema(description = "Charges linked to the subscription") Integer occurrences,
        @Schema(description = "Detector confidence, 0–1") BigDecimal confidence,
        LocalDate nextExpectedDate,
        @Schema(description = "Uncategorized transactions of the merchant") Integer transactionCount,
        @Schema(description = "Possible duplicate: the question to answer") Long duplicateId,
        LocalDate pendingDate,
        @Schema(description = "Signed minor units") Long pendingAmountMinor,
        LocalDate postedDate,
        @Schema(description = "Signed minor units") Long postedAmountMinor,
        @Schema(description = "Price change / missed charge: the alert to answer") Long alertId,
        @Schema(description = "Price change: the amount before") Long previousAmountMinor,
        @Schema(description = "Price change: the new amount; missed: the expected one") Long newAmountMinor,
        @Schema(description = "Missed charge: when it was due") LocalDate dueDate,
        @Schema(description = "Uncategorized merchant: money sent to it") Integer sentCount,
        @Schema(description = "Uncategorized merchant: positive minor units sent") Long sentMinor,
        @Schema(description = "Uncategorized merchant: money received from it") Integer receivedCount,
        @Schema(description = "Uncategorized merchant: positive minor units received") Long receivedMinor,
        @Schema(description = "Uncategorized merchant: its oldest uncategorized transaction") LocalDate firstDate) {

    public enum Kind { SUBSCRIPTION, UNCATEGORIZED_MERCHANT, POSSIBLE_DUPLICATE, PRICE_CHANGE, MISSED_CHARGE }

    /** The inbox: cards to answer, and weaker subscription guesses shown collapsed ("possible"). */
    public record Inbox(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewCard> cards,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Subscription suggestions with confidence between 0.45 and 0.65")
            List<ReviewCard> possible,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Number of cards, for the home badge")
            int count) {}
}
