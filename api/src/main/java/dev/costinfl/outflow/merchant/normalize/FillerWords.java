package dev.costinfl.outflow.merchant.normalize;

import java.util.Set;

/** Step 3c: drop words that describe the payment, not the payee: month names, "LUNA", "FACTURA", "ABONAMENT", … */
public final class FillerWords implements MerchantStep {

    static final Set<String> FILLER = Set.of(
            "IANUARIE", "FEBRUARIE", "MARTIE", "APRILIE", "MAI", "IUNIE", "IULIE", "AUGUST", "SEPTEMBRIE",
            "OCTOMBRIE", "NOIEMBRIE", "DECEMBRIE",
            "JANUARY", "FEBRUARY", "MARCH", "APRIL", "JUNE", "JULY", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
            "LUNA", "FACTURA", "FACT", "NR", "NUMAR", "REF", "REFERINTA", "ABONAMENT", "CARD", "PLATA");

    @Override
    public String apply(String text) {
        var kept = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!word.isEmpty() && !FILLER.contains(word)) {
                kept.append(word).append(' ');
            }
        }
        return kept.toString().strip();
    }
}
