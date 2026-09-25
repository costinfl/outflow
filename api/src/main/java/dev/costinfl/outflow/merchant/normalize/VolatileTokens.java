package dev.costinfl.outflow.merchant.normalize;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 3b: remove what changes between charges from the same merchant: card masks, authorization codes, IBANs,
 * dates, times, processor suffixes after '*', and tokens with digits (terminal, store, reference and invoice numbers,
 * plan codes like {@code P770487}). A brand spelled with a digit or two ({@code 1MINUTE}, {@code 7ELEVEN}: 4+ letters,
 * at most 2 digits) is kept.
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
            Pattern.compile("(?<=\\s|^)[#/*.,:;-]+(?=\\s|$)"));                               // leftover punctuation
    private static final Pattern DIGIT_TOKEN = Pattern.compile("\\b\\S*\\d\\S*\\b");

    @Override
    public String apply(String text) {
        String s = text;
        for (int i = 0; i < PATTERNS.size(); i++) {
            if (i == PATTERNS.size() - 1) {
                s = DIGIT_TOKEN.matcher(s).replaceAll(m -> isBrand(m.group()) ? Matcher.quoteReplacement(m.group()) : " ");
            }
            s = PATTERNS.get(i).matcher(s).replaceAll(" ");
        }
        return Words.collapse(s);
    }

    /** "1MINUTE", "7ELEVEN": 4+ letters and at most 2 digits read as a name, not a number. */
    static boolean isBrand(String token) {
        long letters = token.chars().filter(Character::isLetter).count();
        long digits = token.chars().filter(Character::isDigit).count();
        return letters >= 4 && digits <= 2;
    }
}
