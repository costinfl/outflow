package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import java.time.LocalDate;
import java.util.List;

/**
 * A recurring payment the detector found in one (account, merchant, currency, amount band) group
 * (DESIGN: Recurrence detection). Not stored yet: CP4.2 turns candidates into {@code subscription} rows.
 *
 * @param anchorDay   monthly and yearly: day of month the charge is due on (clamped to shorter months when used);
 *                    weekly: ISO weekday, 1 = Monday; daily: null
 * @param anchorMonth month of year for yearly cadences, else null
 * @param toleranceMinor how far a charge may be from {@code expectedAmountMinor} and still match (2 × MAD)
 * @param direction      OUT: a recurring payment; IN: recurring income
 */
public record Candidate(
        long accountId,
        long merchantId,
        String currency,
        Cadence cadence,
        Integer anchorDay,
        Integer anchorMonth,
        AmountKind amountKind,
        long expectedAmountMinor,
        long toleranceMinor,
        long bandMinMinor,
        long bandMaxMinor,
        LocalDate firstDate,
        LocalDate lastDate,
        LocalDate nextExpectedDate,
        Score score,
        List<Long> transactionIds,
        Direction direction) {

    public enum AmountKind { FIXED, VARIABLE }

    /** PROPOSED is shown in the review inbox; POSSIBLE goes to the collapsed "possible" list. */
    public enum Strength { PROPOSED, POSSIBLE }

    /** The four scoring terms, each in [0, 1] (DESIGN: Recurrence detection, step 3). */
    public record Score(double interval, double amount, double count, double recency) {

        static final double W_INTERVAL = 0.4, W_AMOUNT = 0.2, W_COUNT = 0.2, W_RECENCY = 0.2;

        public double confidence() {
            return W_INTERVAL * interval + W_AMOUNT * amount + W_COUNT * count + W_RECENCY * recency;
        }
    }

    public double confidence() {
        return score.confidence();
    }

    public Strength strength() {
        return confidence() >= RecurrenceDetector.PROPOSE ? Strength.PROPOSED : Strength.POSSIBLE;
    }

    public int occurrences() {
        return transactionIds.size();
    }
}
