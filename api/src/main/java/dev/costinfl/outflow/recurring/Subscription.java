package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A {@code subscription} row: a detector proposal or a user-confirmed recurring payment (or recurring income). */
public record Subscription(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long accountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long merchantId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Cadence cadence,
        @Schema(description = "Day of month the charge is due (clamped in shorter months)") Integer anchorDay,
        @Schema(description = "Month of year, for yearly cadences") Integer anchorMonth,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AmountKind amountKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expectedAmountMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A charge within expected ± tolerance matches")
        long toleranceMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long bandMinMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long bandMaxMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Detector confidence, 0–1") BigDecimal confidence,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate firstSeen,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate lastSeen,
        LocalDate nextExpectedDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) State state,
        EndedBy endedBy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "OUT: a recurring payment; IN: recurring income")
        Direction direction) {

    /** DESIGN: Subscription candidate lifecycle. */
    public enum State { PROPOSED, CONFIRMED, REJECTED, ENDED }

    public enum EndedBy { USER, SYSTEM }

    /** What the user may change when confirming; null fields keep the detected value. */
    public record Edits(String name, Cadence cadence, Long expectedAmountMinor) {

        public static final Edits NONE = new Edits(null, null, null);
    }
}
