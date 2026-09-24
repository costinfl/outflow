package dev.costinfl.outflow.ingest.parse;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Parses statement amount text into signed minor units without ever going through floating point.
 * Strict on purpose: an amount that could be read two ways is an error, not a guess.
 */
public final class MoneyParser {

    private final char decimalSeparator;
    private final Character groupingSeparator;
    private final Pattern shape;

    /**
     * @param decimalSeparator  e.g. {@code '.'} or {@code ','}
     * @param groupingSeparator e.g. {@code ','}, {@code '.'}, {@code ' '}, or {@code null} for none
     */
    public MoneyParser(char decimalSeparator, Character groupingSeparator) {
        if (groupingSeparator != null && groupingSeparator == decimalSeparator) {
            throw new IllegalArgumentException("decimal and grouping separator must differ");
        }
        this.decimalSeparator = decimalSeparator;
        this.groupingSeparator = groupingSeparator;
        String d = Pattern.quote(String.valueOf(decimalSeparator));
        String digits = groupingSeparator == null
                ? "\\d+"
                : "(?:\\d{1,3}(?:" + Pattern.quote(String.valueOf(groupingSeparator)) + "\\d{3})+|\\d+)";
        this.shape = Pattern.compile("[+-]?" + digits + "(?:" + d + "\\d+)?");
    }

    /** @throws IllegalArgumentException with a user-readable reason */
    public long toMinor(String text, Currency currency) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("amount is empty");
        }
        // Spaces (incl. no-break spaces) are only formatting, unless space is the grouping separator.
        String s = text.strip().replace(' ', ' ').replace(' ', ' ');
        if (groupingSeparator == null || groupingSeparator != ' ') {
            s = s.replace(" ", "");
        }
        // Trailing minus, as some banks print it: "123,45-".
        if (s.endsWith("-") && !s.startsWith("-") && !s.startsWith("+")) {
            s = "-" + s.substring(0, s.length() - 1);
        }
        if (!shape.matcher(s).matches()) {
            throw new IllegalArgumentException("unreadable amount '" + text + "'");
        }
        if (groupingSeparator != null) {
            s = s.replace(String.valueOf(groupingSeparator), "");
        }
        var amount = new BigDecimal(s.replace(decimalSeparator, '.'));
        int fractionDigits = currency.getDefaultFractionDigits();
        if (amount.scale() > fractionDigits) {
            throw new IllegalArgumentException(
                    "amount '" + text + "' has more than " + fractionDigits + " decimals for " + currency);
        }
        try {
            return amount.movePointRight(fractionDigits).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount '" + text + "' is out of range");
        }
    }
}
