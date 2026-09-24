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
        var byAmount = new ArrayList<>(occurrences);
        byAmount.sort(Comparator.comparingLong(Occurrence::amountMinor).thenComparing(Occurrence::date));
        var bands = new ArrayList<List<Occurrence>>();
        var band = new ArrayList<Occurrence>();
        for (Occurrence o : byAmount) {
            if (!band.isEmpty() && gapAbove25Pct(band.getLast().amountMinor(), o.amountMinor())) {
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

    private static List<Occurrence> inDateOrder(List<Occurrence> band) {
        return band.stream().sorted(Comparator.comparing(Occurrence::date).thenComparingLong(Occurrence::transactionId)).toList();
    }
}
