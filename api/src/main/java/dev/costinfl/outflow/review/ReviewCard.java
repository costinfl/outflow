package dev.costinfl.outflow.review;

import dev.costinfl.outflow.recurring.Cadence;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One question for the user (DESIGN: Review inbox). One card = one merchant, never one transaction (a possible duplicate
 * is about two). Subscription fields are set on SUBSCRIPTION cards, {@code transactionCount} on UNCATEGORIZED_MERCHANT
 * cards, the pending/posted fields on POSSIBLE_DUPLICATE cards.
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
        @Schema(description = "Signed minor units") Long postedAmountMinor) {

    public enum Kind { SUBSCRIPTION, UNCATEGORIZED_MERCHANT, POSSIBLE_DUPLICATE }

    /** The inbox: cards to answer, and weaker subscription guesses shown collapsed ("possible"). */
    public record Inbox(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewCard> cards,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Subscription suggestions with confidence between 0.45 and 0.65")
            List<ReviewCard> possible,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Number of cards, for the home badge")
            int count) {}
}
