package dev.costinfl.outflow.recurring;

/**
 * Cadences the detector fits (DESIGN: Recurrence detection, table in step 2). Every cadence is anchored: a charge
 * belongs to the period whose due date it is closest to, not to a fixed number of days after the last one. Monthly: a
 * day of month; quarterly: a day of month in every third month; yearly: a month and day; weekly: a weekday; bi-weekly:
 * a weekday in every other week; daily: every day (a weekend without charges is no gap).
 */
public enum Cadence {

    DAILY(0, 10, 1.0),
    WEEKLY(1, 4, 7.0),
    BIWEEKLY(2, 3, 14.0),
    MONTHLY(3, 3, 30.44),
    QUARTERLY(5, 3, 91.31),
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

    /**
     * What one charge of {@code amountMinor} costs per month (DESIGN: yearly ÷ 12, weekly × 4.33; daily × 30.42, a
     * twelfth of 365; bi-weekly × 26 ÷ 12; quarterly ÷ 3), rounded half up in integer arithmetic.
     */
    public long monthlyMinor(long amountMinor) {
        return switch (this) {
            case DAILY -> Math.floorDiv(amountMinor * 3042 + 50, 100);
            case WEEKLY -> Math.floorDiv(amountMinor * 433 + 50, 100);
            case BIWEEKLY -> Math.floorDiv(amountMinor * 26 + 6, 12);
            case MONTHLY -> amountMinor;
            case QUARTERLY -> Math.floorDiv(amountMinor + 1, 3);
            case YEARLY -> Math.floorDiv(amountMinor + 6, 12);
        };
    }

    /** What one charge of {@code amountMinor} costs per year. */
    public long yearlyMinor(long amountMinor) {
        return switch (this) {
            case DAILY -> amountMinor * 365;
            case WEEKLY -> amountMinor * 52;
            case BIWEEKLY -> amountMinor * 26;
            case MONTHLY -> amountMinor * 12;
            case QUARTERLY -> amountMinor * 4;
            case YEARLY -> amountMinor;
        };
    }
}
