package dev.costinfl.outflow.merchant.normalize;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Step 1: uppercase, strip diacritics (ă â î ş ș ţ ț …), drop quotes, collapse whitespace. */
public final class BasicCleanup implements MerchantStep {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern QUOTES = Pattern.compile("[\"'`´]");

    @Override
    public String apply(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("");
        s = QUOTES.matcher(s).replaceAll("");
        return Words.collapse(s.toUpperCase(Locale.ROOT));
    }
}
