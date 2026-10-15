package dev.costinfl.outflow.split;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * How much of a shared expense each person owes (CP6.21). One mode per split:
 * <ul>
 *   <li>{@code EQUAL}: the user and each person alike;</li>
 *   <li>{@code PERCENT}: each person's percentage (at most 100 together), the rest is the user's;</li>
 *   <li>{@code FIXED}: each person's amount (at most the total together), the rest is the user's;</li>
 *   <li>{@code SHARES}: weights, the user's included (e.g. 2 for a couple, 1 each for the others).</li>
 * </ul>
 * Each person's part is rounded down to the minor unit and the odd cents are the user's, so the parts always add up to
 * the total exactly and nobody is asked for more than their part. Amounts are minor units, never floating point.
 */
public final class SplitCalculator {

    public enum Mode { EQUAL, PERCENT, FIXED, SHARES }

    /** One person as entered; only the field of the split's mode is used. */
    public record PersonInput(String name, BigDecimal percent, Long fixedMinor, Integer shares) {}

    /** One person as stored: the mode's value (the others null) and what they owe. */
    public record PersonShare(String name, BigDecimal percent, Long fixedMinor, Integer shares, long amountMinor) {}

    /** The people's parts, in input order, and the user's own part; they add up to the total. */
    public record Allocation(List<PersonShare> people, long mineMinor) {

        public long othersMinor() {
            return people.stream().mapToLong(PersonShare::amountMinor).sum();
        }
    }

    static final int MAX_PEOPLE = 20;
    static final int MAX_NAME = 60;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SplitCalculator() {}

    /**
     * @param totalMinor the expense, positive minor units
     * @param myShares the user's weight in {@code SHARES} mode (0 = the user only paid); ignored otherwise
     * @throws IllegalArgumentException when the input does not make a split, with a message for the user
     */
    public static Allocation allocate(long totalMinor, Mode mode, Integer myShares, List<PersonInput> people) {
        if (totalMinor <= 0) {
            throw new IllegalArgumentException("Only money out can be split");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Choose how to split: EQUAL, PERCENT, FIXED or SHARES");
        }
        if (people == null || people.isEmpty()) {
            throw new IllegalArgumentException("Add at least one person to split with");
        }
        if (people.size() > MAX_PEOPLE) {
            throw new IllegalArgumentException("At most " + MAX_PEOPLE + " people per split");
        }
        var names = new HashSet<String>();
        var cleaned = new ArrayList<String>();
        for (PersonInput p : people) {
            String name = p.name() == null ? "" : p.name().strip().replaceAll("\\s+", " ");
            if (name.isEmpty() || name.length() > MAX_NAME) {
                throw new IllegalArgumentException("Each person needs a name of at most " + MAX_NAME + " characters");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(name + " is listed twice");
            }
            cleaned.add(name);
        }
        BigDecimal total = BigDecimal.valueOf(totalMinor);
        var shares = new ArrayList<PersonShare>();
        switch (mode) {
            case EQUAL -> {
                long each = total.divide(BigDecimal.valueOf(people.size() + 1), 0, RoundingMode.DOWN).longValueExact();
                cleaned.forEach(n -> shares.add(new PersonShare(n, null, null, null, each)));
            }
            case PERCENT -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < people.size(); i++) {
                    BigDecimal pct = people.get(i).percent();
                    if (pct == null || pct.signum() <= 0 || pct.compareTo(HUNDRED) > 0 || pct.stripTrailingZeros().scale() > 2) {
                        throw new IllegalArgumentException(cleaned.get(i) + ": a percentage above 0, at most 100, two decimals");
                    }
                    sum = sum.add(pct);
                    long amount = total.multiply(pct).divide(HUNDRED, 0, RoundingMode.DOWN).longValueExact();
                    shares.add(new PersonShare(cleaned.get(i), pct.stripTrailingZeros(), null, null, amount));
                }
                if (sum.compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("The percentages add up to " + sum.stripTrailingZeros().toPlainString()
                            + "%, more than 100%");
                }
            }
            case FIXED -> {
                long sum = 0;
                for (int i = 0; i < people.size(); i++) {
                    Long fixed = people.get(i).fixedMinor();
                    if (fixed == null || fixed <= 0) {
                        throw new IllegalArgumentException(cleaned.get(i) + ": an amount above 0");
                    }
                    sum = Math.addExact(sum, fixed);
                    shares.add(new PersonShare(cleaned.get(i), null, fixed, null, fixed));
                }
                if (sum > totalMinor) {
                    throw new IllegalArgumentException("The amounts add up to more than the expense");
                }
            }
            case SHARES -> {
                int mine = myShares == null ? 1 : myShares;
                if (mine < 0 || mine > 1000) {
                    throw new IllegalArgumentException("Your shares: 0 to 1000");
                }
                long weights = mine;
                for (int i = 0; i < people.size(); i++) {
                    Integer s = people.get(i).shares();
                    if (s == null || s <= 0 || s > 1000) {
                        throw new IllegalArgumentException(cleaned.get(i) + ": shares from 1 to 1000");
                    }
                    weights += s;
                }
                for (int i = 0; i < people.size(); i++) {
                    int s = people.get(i).shares();
                    long amount = total.multiply(BigDecimal.valueOf(s))
                            .divide(BigDecimal.valueOf(weights), 0, RoundingMode.DOWN).longValueExact();
                    shares.add(new PersonShare(cleaned.get(i), null, null, s, amount));
                }
            }
        }
        var allocation = new Allocation(List.copyOf(shares), 0);
        long others = allocation.othersMinor();
        if (others == 0) {
            throw new IllegalArgumentException("Nobody would owe anything: the parts round to zero");
        }
        return new Allocation(allocation.people(), totalMinor - others);
    }
}
