package dev.costinfl.outflow.ingest.parse;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** A checksum-validated IBAN, held in memory only. Persist {@link #masked()} and an HMAC of {@link #value()}, never the value. */
public final class Iban {

    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final Pattern CANDIDATE = Pattern.compile("\\b[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]){11,30}\\b");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private final String value;

    private Iban(String value) {
        this.value = value;
    }

    /** Parses an IBAN in electronic or printed form (spaces allowed); empty unless the shape and mod-97 check pass. */
    public static Optional<Iban> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String compact = text.replace(" ", "").toUpperCase(Locale.ROOT);
        return SHAPE.matcher(compact).matches() && checksumOk(compact) ? Optional.of(new Iban(compact)) : Optional.empty();
    }

    /** The first valid IBAN appearing anywhere in free text, e.g. a statement preamble line. */
    public static Optional<Iban> find(String text) {
        var m = CANDIDATE.matcher(text.toUpperCase(Locale.ROOT));
        while (m.find()) {
            var iban = parse(m.group());
            if (iban.isPresent()) {
                return iban;
            }
        }
        return Optional.empty();
    }

    /** Free text with every valid IBAN replaced by its masked form, for showing raw descriptions (spec question 9). */
    public static String maskAll(String text) {
        var m = CANDIDATE.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    parse(m.group()).map(Iban::masked).orElse(m.group())));
        }
        return m.appendTail(out).toString();
    }

    private static boolean checksumOk(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        var digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
    }

    /** Electronic form, e.g. {@code RO49AAAA1B31007593840000}. Only for hashing; never log or store it. */
    public String value() {
        return value;
    }

    /** Display form: country + check digits + last four, e.g. {@code RO49 •••• 0000}. */
    public String masked() {
        return value.substring(0, 4) + " •••• " + value.substring(value.length() - 4);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Iban other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return masked();
    }
}
