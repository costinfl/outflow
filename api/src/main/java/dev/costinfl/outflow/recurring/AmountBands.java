package dev.costinfl.outflow.recurring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits one merchant's charges into amount streams (DESIGN: Recurrence detection, step 1): sort by amount and cut
 * where the next amount is more than 25% above the previous one. A fixed 49.99 plan and occasional 300 purchases at the
 * same store become two groups, each tested on its own.
 */
public final class AmountBands {

    private AmountBands() {}

    /** Bands in ascending amount order; each band's occurrences in date order. */
    public static List<List<Occurrence>> split(List<Occurrence> occurrences) {
        return split(occurrences, List.of());
    }

    /**
     * As {@link #split(List)}, and also cut at each of the user's {@code cuts} (CP6.16, "two plans from one merchant"):
     * amounts below a cut and amounts at or above it never share a band.
     */
    public static List<List<Occurrence>> split(List<Occurrence> occurrences, List<Long> cuts) {
        var byAmount = new ArrayList<>(occurrences);
        byAmount.sort(Comparator.comparingLong(Occurrence::amountMinor).thenComparing(Occurrence::date));
        var bands = new ArrayList<List<Occurrence>>();
        var band = new ArrayList<Occurrence>();
        for (Occurrence o : byAmount) {
            if (!band.isEmpty() && (gapAbove25Pct(band.getLast().amountMinor(), o.amountMinor())
                    || crossesCut(band.getLast().amountMinor(), o.amountMinor(), cuts))) {
                bands.add(inDateOrder(band));
                band = new ArrayList<>();
            }
            band.add(o);
        }
        if (!band.isEmpty()) {
            bands.add(inDateOrder(band));
        }
        return bands;
    }

    /** {@code next > previous × 1.25}, in integer arithmetic. */
    static boolean gapAbove25Pct(long previous, long next) {
        return 4 * (next - previous) > previous;
    }

    /** A cut lies in (previous, next]: previous is below it, next at or above it. */
    static boolean crossesCut(long previous, long next, List<Long> cuts) {
        return cuts.stream().anyMatch(cut -> previous < cut && cut <= next);
    }

    private static List<Occurrence> inDateOrder(List<Occurrence> band) {
        return band.stream().sorted(Comparator.comparing(Occurrence::date).thenComparingLong(Occurrence::transactionId)).toList();
    }
}
