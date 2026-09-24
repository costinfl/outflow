package dev.costinfl.outflow.recurring;

/**
 * Cadences the detector fits (DESIGN: Recurrence detection, table in step 2). Monthly and yearly are calendar-anchored:
 * a charge belongs to the period whose anchor date it is closest to, not to a fixed number of days after the last one.
 * Weekly and daily cadences come in M5 (CP5.3).
 */
public enum Cadence {

    MONTHLY(3, 3, 30.44),
    YEARLY(7, 2, 365.25);

    /** Days a charge may sit from its anchor date and still count as on time. */
    public final int toleranceDays;
    /** Occurrences needed before the cadence is tried at all. */
    public final int minCount;
    /** Average length of one step in days, for recency only. */
    public final double stepDays;

    Cadence(int toleranceDays, int minCount, double stepDays) {
        this.toleranceDays = toleranceDays;
        this.minCount = minCount;
        this.stepDays = stepDays;
    }

    /** What one charge of {@code amountMinor} costs per month (DESIGN: yearly ÷ 12), rounded half up. */
    public long monthlyMinor(long amountMinor) {
        return switch (this) {
            case MONTHLY -> amountMinor;
            case YEARLY -> Math.floorDiv(amountMinor + 6, 12);
        };
    }

    /** What one charge of {@code amountMinor} costs per year. */
    public long yearlyMinor(long amountMinor) {
        return switch (this) {
            case MONTHLY -> amountMinor * 12;
            case YEARLY -> amountMinor;
        };
    }
}
