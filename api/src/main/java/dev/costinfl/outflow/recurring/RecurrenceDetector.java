package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.Candidate.Score;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Finds recurring payments in one merchant's outgoing charges (DESIGN: Recurrence detection, steps 1–4). Pure: the
 * caller supplies the charges and today's date.
 *
 * <p>Amounts stay {@code long} minor units; only the dimensionless scores (shares, coefficient of variation) are
 * {@code double}.
 */
public final class RecurrenceDetector {

    /** Candidates at or above this confidence are proposed; between {@link #POSSIBLE} and this they are "possible". */
    public static final double PROPOSE = 0.65;
    public static final double POSSIBLE = 0.45;
    /** Coefficient of variation up to which an amount counts as fixed. */
    static final double FIXED_MAX_CV = 0.02;

    /** One merchant's charges (or payments received) on one account in one currency, before banding. */
    public record Group(long accountId, long merchantId, String currency, List<Occurrence> occurrences,
            Direction direction) {

        /** Outgoing charges. */
        public Group(long accountId, long merchantId, String currency, List<Occurrence> occurrences) {
            this(accountId, merchantId, currency, occurrences, Direction.OUT);
        }
    }

    /**
     * At most one candidate per amount band: the best-fitting cadence, if it scores at least {@link #POSSIBLE}. A band
     * paid twice a month on two days of its own (a salary in two parts, on the 10th and the 25th) fits no cadence as
     * a whole; it is split by day of month into two monthly streams.
     */
    public List<Candidate> detect(Group group, LocalDate today) {
        var found = new ArrayList<Candidate>();
        for (List<Occurrence> band : AmountBands.split(group.occurrences())) {
            var best = best(group, band, today);
            if (best.isPresent()) {
                found.add(best.get());
            } else {
                found.addAll(twiceMonthly(group, band, today));
            }
        }
        return found;
    }

    /** Days between the two parts of a twice-monthly band, at least, on the circle of a month's days. */
    static final int PARTS_APART_DAYS = 5;

    /**
     * Two monthly streams from one band, when its days of month form two clusters at least {@value #PARTS_APART_DAYS}
     * days apart on both sides and each cluster fits monthly on its own. Otherwise none.
     */
    List<Candidate> twiceMonthly(Group group, List<Occurrence> band, LocalDate today) {
        if (band.size() < 2 * Cadence.MONTHLY.minCount) {
            return List.of();
        }
        int[] days = band.stream().mapToInt(o -> o.date().getDayOfMonth()).distinct().sorted().toArray();
        if (days.length < 2) {
            return List.of();
        }
        // The two widest gaps between neighbouring days (around the month, 31 → 1) cut the days into two arcs.
        int first = -1, second = -1;
        for (int i = 0; i < days.length; i++) {
            if (first < 0 || gapAfter(days, i) > gapAfter(days, first)) {
                second = first;
                first = i;
            } else if (second < 0 || gapAfter(days, i) > gapAfter(days, second)) {
                second = i;
            }
        }
        if (second < 0 || gapAfter(days, second) < PARTS_APART_DAYS) {
            return List.of();
        }
        int lo = Math.min(first, second), hi = Math.max(first, second);
        // Arc A: the days after position lo up to position hi; arc B: the rest.
        var arcA = new java.util.HashSet<Integer>();
        for (int i = lo + 1; i <= hi; i++) {
            arcA.add(days[i]);
        }
        List<Occurrence> a = band.stream().filter(o -> arcA.contains(o.date().getDayOfMonth())).toList();
        List<Occurrence> b = band.stream().filter(o -> !arcA.contains(o.date().getDayOfMonth())).toList();
        var fits = new ArrayList<Candidate>();
        for (List<Occurrence> part : List.of(a, b)) {
            if (part.size() < Cadence.MONTHLY.minCount) {
                return List.of();
            }
            Candidate fit = fit(group, part, Cadence.MONTHLY, today);
            if (fit == null || fit.confidence() < POSSIBLE) {
                return List.of();
            }
            fits.add(fit);
        }
        return fits;
    }

    /** Days from {@code days[i]} to the next day in the list, wrapping from the last day of a month to the first. */
    private static int gapAfter(int[] days, int i) {
        return i + 1 < days.length ? days[i + 1] - days[i] : days[0] + 31 - days[i];
    }

    Optional<Candidate> best(Group group, List<Occurrence> band, LocalDate today) {
        Candidate best = null;
        for (Cadence cadence : Cadence.values()) {
            if (band.size() < cadence.minCount) {
                continue;
            }
            Candidate fit = fit(group, band, cadence, today);
            if (fit != null && (best == null || fit.confidence() > best.confidence())) {
                best = fit;
            }
        }
        return Optional.ofNullable(best).filter(c -> c.confidence() >= POSSIBLE);
    }

    /** The candidate for one cadence, or null when the charges do not step one period at a time (median gap ≠ 1). */
    Candidate fit(Group group, List<Occurrence> band, Cadence cadence, LocalDate today) {
        int n = band.size();
        var anchor = Anchor.of(cadence, band.stream().map(Occurrence::date).toList());
        long[] period = new long[n];
        boolean[] onTime = new boolean[n];
        for (int i = 0; i < n; i++) {
            LocalDate date = band.get(i).date();
            period[i] = anchor.nearestPeriod(date);
            onTime[i] = anchor.distance(date, period[i]) <= cadence.toleranceDays;
        }
        long[] gaps = new long[n - 1];
        int regular = 0;
        for (int i = 0; i < n - 1; i++) {
            gaps[i] = anchor.gap(period[i], period[i + 1]);
            if (gaps[i] == 1 && onTime[i] && onTime[i + 1]) {
                regular++;
            }
        }
        if (lowerMedian(gaps) != 1) {
            return null;
        }

        long[] amounts = band.stream().mapToLong(Occurrence::amountMinor).toArray();
        double cv = coefficientOfVariation(amounts);
        long[] lastThree = Arrays.copyOfRange(amounts, Math.max(0, n - 3), n);
        LocalDate last = band.getLast().date();
        var score = new Score(
                (double) regular / (n - 1),
                Math.max(0, 1 - cv),
                Math.min(1, n / (2.0 * cadence.minCount)),
                recency(ChronoUnit.DAYS.between(last, today) / cadence.stepDays));
        return new Candidate(group.accountId(), group.merchantId(), group.currency(), cadence,
                cadence == Cadence.DAILY ? null : anchor.day(), cadence == Cadence.YEARLY ? anchor.month() : null,
                cv <= FIXED_MAX_CV ? AmountKind.FIXED : AmountKind.VARIABLE,
                median(lastThree), 2 * medianAbsoluteDeviation(amounts),
                Arrays.stream(amounts).min().orElseThrow(), Arrays.stream(amounts).max().orElseThrow(),
                band.getFirst().date(), last, anchor.dateIn(period[n - 1] + 1),
                score, band.stream().map(Occurrence::transactionId).toList(), group.direction());
    }

    /** 1 within 1.5 expected steps of today, falling linearly to 0 at 3 steps. */
    static double recency(double stepsSinceLast) {
        if (stepsSinceLast <= 1.5) {
            return 1;
        }
        return stepsSinceLast >= 3 ? 0 : (3 - stepsSinceLast) / 1.5;
    }

    static double coefficientOfVariation(long[] amounts) {
        double mean = Arrays.stream(amounts).average().orElseThrow();
        double variance = Arrays.stream(amounts).mapToDouble(a -> (a - mean) * (a - mean)).sum() / amounts.length;
        return mean == 0 ? 0 : Math.sqrt(variance) / mean;
    }

    /** Median of amounts; the mean of the middle two (rounded down) for an even count. */
    static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : Math.floorDiv(sorted[mid - 1] + sorted[mid], 2);
    }

    static long medianAbsoluteDeviation(long[] values) {
        long median = median(values);
        return median(Arrays.stream(values).map(v -> Math.abs(v - median)).toArray());
    }

    /** The lower middle value, so an even split between on-time and missed steps still reads as one step. */
    static long lowerMedian(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(sorted.length - 1) / 2];
    }

    /**
     * Where a cadence's charges are due: a day of month (monthly), a month and day (yearly), a weekday (weekly, {@code
     * day} = ISO weekday) or every day (daily). Periods are numbered (months since year 0, the year, weeks or days since
     * 1970) so that consecutive periods differ by one.
     */
    record Anchor(Cadence cadence, int month, int day) {

        /** The anchor stored with a subscription, to compute its due dates. */
        static Anchor of(Subscription s) {
            return new Anchor(s.cadence(), s.anchorMonth() == null ? 0 : s.anchorMonth(),
                    s.anchorDay() == null ? 0 : s.anchorDay());
        }

        /**
         * Monthly: the median day of month. Yearly: the median date across years, measured around the first one.
         * Weekly: the most frequent weekday (the earliest on a tie). Daily: none.
         */
        static Anchor of(Cadence cadence, List<LocalDate> dates) {
            if (cadence == Cadence.DAILY) {
                return new Anchor(cadence, 0, 0);
            }
            if (cadence == Cadence.WEEKLY) {
                int[] counts = new int[8];
                dates.forEach(d -> counts[d.getDayOfWeek().getValue()]++);
                int best = 1;
                for (int d = 2; d <= 7; d++) {
                    if (counts[d] > counts[best]) {
                        best = d;
                    }
                }
                return new Anchor(cadence, 0, best);
            }
            if (cadence == Cadence.MONTHLY) {
                long[] days = dates.stream().mapToLong(LocalDate::getDayOfMonth).toArray();
                return new Anchor(cadence, 0, (int) lowerMedian(days));
            }
            LocalDate first = dates.getFirst();
            long[] offsets = dates.stream().mapToLong(d -> {
                int offset = d.getDayOfYear() - first.getDayOfYear();
                return offset > 182 ? offset - 365 : offset < -182 ? offset + 365 : offset;
            }).toArray();
            LocalDate median = first.plusDays(lowerMedian(offsets));
            return new Anchor(cadence, median.getMonthValue(), median.getDayOfMonth());
        }

        /** The due date in a period, clamped to the month's length (31 → 30, 28 or 29; 29 Feb → 28 Feb). */
        LocalDate dateIn(long period) {
            if (cadence == Cadence.DAILY) {
                return LocalDate.ofEpochDay(period);
            }
            if (cadence == Cadence.WEEKLY) {
                return LocalDate.ofEpochDay(7 * period + weekOffset());
            }
            if (cadence == Cadence.MONTHLY) {
                var ym = YearMonth.of((int) Math.floorDiv(period, 12), (int) Math.floorMod(period, 12) + 1);
                return ym.atDay(Math.min(day, ym.lengthOfMonth()));
            }
            return MonthDay.of(month, day).atYear((int) period);
        }

        /** The period whose due date is closest to {@code date}: a charge on 1 March can pay February's 31st. */
        long nearestPeriod(LocalDate date) {
            long own = switch (cadence) {
                case DAILY -> date.toEpochDay();
                case WEEKLY -> Math.floorDiv(date.toEpochDay() - weekOffset(), 7);
                case MONTHLY -> date.getYear() * 12L + date.getMonthValue() - 1;
                case YEARLY -> date.getYear();
            };
            long best = own;
            for (long p = own - 1; p <= own + 1; p++) {
                if (distance(date, p) < distance(date, best)) {
                    best = p;
                }
            }
            return best;
        }

        /** 1970-01-01 (epoch day 0) is a Thursday: the offset of this anchor's weekday within an epoch week. */
        private long weekOffset() {
            return Math.floorMod(day - 4, 7);
        }

        /** Periods between two charges. Daily: a Friday → Monday gap over a weekend without charges counts as one. */
        long gap(long from, long to) {
            long diff = to - from;
            if (cadence == Cadence.DAILY && diff > 1 && diff <= 3) {
                for (long d = from + 1; d < to; d++) {
                    var dow = LocalDate.ofEpochDay(d).getDayOfWeek();
                    if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                        return diff;
                    }
                }
                return 1;
            }
            return diff;
        }

        /** The due date after the one {@code date} belongs to. */
        LocalDate nextDue(LocalDate date) {
            return dateIn(nearestPeriod(date) + 1);
        }

        /** Days from the due date, or from the next business day when the due date falls on a weekend. */
        long distance(LocalDate date, long period) {
            LocalDate due = dateIn(period);
            return Math.min(Math.abs(ChronoUnit.DAYS.between(due, date)),
                    Math.abs(ChronoUnit.DAYS.between(nextBusinessDay(due), date)));
        }

        static LocalDate nextBusinessDay(LocalDate date) {
            return switch (date.getDayOfWeek()) {
                case SATURDAY -> date.plusDays(2);
                case SUNDAY -> date.plusDays(1);
                default -> date;
            };
        }
    }
}
