package dev.costinfl.outflow.txn;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The months a figure covers: one calendar month, or the "last 3 months" smoothing window (DESIGN: Home screen)
 * ending with the selected month. Its SQL is {@link Scope#PERIOD}.
 *
 * @param last   the selected month, the window's last
 * @param months 1 or 3
 */
public record Period(YearMonth last, int months) {

    public Period {
        if (months != 1 && months != 3) {
            throw new IllegalArgumentException("months must be 1 or 3");
        }
    }

    public static Period month(YearMonth month) {
        return new Period(month, 1);
    }

    public YearMonth first() {
        return last.minusMonths(months - 1);
    }

    public boolean contains(YearMonth month) {
        return !month.isBefore(first()) && !month.isAfter(last);
    }

    /** The two parameters of {@link Scope#PERIOD}: first day, and the day after the last. */
    public LocalDate from() {
        return first().atDay(1);
    }

    public LocalDate toExclusive() {
        return last.plusMonths(1).atDay(1);
    }
}
