package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A {@code subscription} row: a detector proposal or a user-confirmed recurring payment. */
public record Subscription(
        long id,
        long accountId,
        long merchantId,
        String name,
        String currency,
        Cadence cadence,
        Integer anchorDay,
        Integer anchorMonth,
        AmountKind amountKind,
        long expectedAmountMinor,
        long toleranceMinor,
        long bandMinMinor,
        long bandMaxMinor,
        BigDecimal confidence,
        LocalDate firstSeen,
        LocalDate lastSeen,
        LocalDate nextExpectedDate,
        State state,
        EndedBy endedBy) {

    /** DESIGN: Subscription candidate lifecycle. */
    public enum State { PROPOSED, CONFIRMED, REJECTED, ENDED }

    public enum EndedBy { USER, SYSTEM }

    /** What the user may change when confirming; null fields keep the detected value. */
    public record Edits(String name, Cadence cadence, Long expectedAmountMinor) {

        public static final Edits NONE = new Edits(null, null, null);
    }
}
