package dev.costinfl.outflow.merchant.normalize;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Step 3b: remove what changes between charges from the same merchant: card masks, authorization codes, IBANs,
 * dates, times, processor suffixes after '*', and every token containing a digit (terminal, store, reference and
 * invoice numbers, plan codes like {@code P770487}).
 */
public final class VolatileTokens implements MerchantStep {

    private static final List<Pattern> PATTERNS = List.of(
            // "CARD ****4412", "XXXX4412", "541234******1234"
            Pattern.compile("(?:\\bCARD\\s*)?(?<![A-Z])\\d{0,6}[*X]{2,}[\\s*X]*\\d{2,4}\\b"),
            Pattern.compile("\\b(?:AUTORIZARE|AUTORIZATIE|COD AUTORIZARE|AUTH(?:ORIZATION)?(?: CODE)?|APPROVAL(?: CODE)?)\\s*:?\\s*[A-Z0-9]+\\b"),
            Pattern.compile("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]){11,30}\\b"),                  // IBAN
            Pattern.compile("\\b\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}\\b"),                      // dates
            Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),                            // times
            Pattern.compile("(?<=\\S)\\*\\S*"),                                               // "AMZN MKTP DE*2B4XY7Z"
            Pattern.compile("\\b\\S*\\d\\S*\\b"),                                             // any token with a digit
            Pattern.compile("(?<=\\s|^)[#/*.,:;-]+(?=\\s|$)"));                               // leftover punctuation

    @Override
    public String apply(String text) {
        String s = text;
        for (Pattern p : PATTERNS) {
            s = p.matcher(s).replaceAll(" ");
        }
        return Words.collapse(s);
    }
}
