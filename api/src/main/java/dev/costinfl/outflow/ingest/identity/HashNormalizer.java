package dev.costinfl.outflow.ingest.identity;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code key_v1} description normalization for identity hashing (DESIGN: Transaction identity). Stricter than display
 * normalization: uppercase, no diacritics, single spaces, volatile tokens removed.
 *
 * <p><b>Frozen.</b> Changing any rule here changes identity keys and would duplicate every re-imported transaction.
 * A new rule means a new version ({@code key_v2}) alongside this one, never an edit; {@code IdentityKeysTest} pins
 * known outputs to catch accidental changes.
 */
public final class HashNormalizer {

    public static final String VERSION = "key_v1";

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** Applied in order, each replaced by a single space. */
    private static final List<Pattern> VOLATILE = List.of(
            // card masks: ****4412, XXXX4412, 4412******1234, 5412 **** **** 1234
            Pattern.compile("\\b\\d{0,6}\\s?(?:[*X]{2,}\\s?)+\\d{2,4}\\b"),
            Pattern.compile("(?<![A-Z0-9])(?:[*X]{2,}\\s?)+\\d{2,4}\\b"),
            // authorization / approval codes with their label
            Pattern.compile("\\b(?:AUTORIZARE|AUTORIZATIE|AUTH(?:ORIZATION)?(?: CODE)?|COD AUTORIZARE|APPROVAL(?: CODE)?)\\s*:?\\s*[A-Z0-9]+\\b"),
            // times: 14:05, 14:05:33
            Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
            // dates: 2026-01-12, 12.01.2026, 12/01/2026, 12-01-26
            Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b"),
            Pattern.compile("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b"));

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private HashNormalizer() {}

    public static String normalize(String description) {
        String s = Normalizer.normalize(description, Normalizer.Form.NFD);
        s = COMBINING_MARKS.matcher(s).replaceAll("");
        s = s.toUpperCase(Locale.ROOT);
        s = WHITESPACE.matcher(s).replaceAll(" ");
        for (Pattern p : VOLATILE) {
            s = p.matcher(s).replaceAll(" ");
        }
        return WHITESPACE.matcher(s).replaceAll(" ").strip();
    }
}
