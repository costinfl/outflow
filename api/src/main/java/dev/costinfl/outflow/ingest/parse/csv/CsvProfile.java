package dev.costinfl.outflow.ingest.parse.csv;

import java.nio.charset.Charset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Column mapping for one CSV export format, loaded from YAML ({@code classpath:parsers/*.yml}).
 * Column names are matched case-insensitively against the header row, which may be preceded by preamble lines.
 *
 * @param currency         fixed ISO currency for formats without a currency column
 * @param ibanInPreamble   look for the account IBAN in the lines above the header
 */
public record CsvProfile(
        String id,
        String name,
        String encoding,
        String delimiter,
        String dateFormat,
        String valueDateFormat,
        String decimalSeparator,
        String groupingSeparator,
        String currency,
        boolean ibanInPreamble,
        Columns columns) {

    /**
     * Either {@code amount} (signed) or {@code debit} + {@code credit} (both unsigned). Description may be several
     * columns, joined with a space.
     */
    public record Columns(
            String bookingDate,
            String valueDate,
            String amount,
            String debit,
            String credit,
            String currency,
            List<String> description,
            String reference,
            String accountIban,
            String status,
            List<String> pendingValues) {

        public Columns {
            description = description == null ? List.of() : List.copyOf(description);
            pendingValues = pendingValues == null ? List.of() : List.copyOf(pendingValues);
        }

        public Columns(String bookingDate, String valueDate, String amount, String debit, String credit, String currency,
                List<String> description, String reference, String accountIban) {
            this(bookingDate, valueDate, amount, debit, credit, currency, description, reference, accountIban, null, null);
        }

        /** Every mapped header name; all must be present in the file for it to match this profile. */
        public List<String> required() {
            var all = new ArrayList<String>();
            Stream.of(bookingDate, valueDate, amount, debit, credit, currency, reference, accountIban, status)
                    .filter(Objects::nonNull)
                    .forEach(all::add);
            all.addAll(description);
            return all;
        }
    }

    public CsvProfile {
        encoding = encoding == null ? "UTF-8" : encoding;
        delimiter = delimiter == null ? "," : delimiter;
        decimalSeparator = decimalSeparator == null ? "." : decimalSeparator;
        valueDateFormat = valueDateFormat == null ? dateFormat : valueDateFormat;
        validate(id, encoding, delimiter, dateFormat, decimalSeparator, groupingSeparator, currency, columns);
    }

    private static void validate(String id, String encoding, String delimiter, String dateFormat,
            String decimalSeparator, String groupingSeparator, String currency, Columns c) {
        require(id != null && id.matches("[a-z0-9][a-z0-9-]*"), "id must be lowercase letters, digits and dashes");
        String where = "profile '" + id + "': ";
        require(Charset.isSupported(encoding), where + "unsupported encoding " + encoding);
        require(delimiter.length() == 1, where + "delimiter must be one character");
        require(decimalSeparator.length() == 1, where + "decimalSeparator must be one character");
        require(groupingSeparator == null || groupingSeparator.length() == 1,
                where + "groupingSeparator must be one character");
        require(dateFormat != null, where + "dateFormat is required");
        DateTimeFormatter.ofPattern(dateFormat);
        require(c != null, where + "columns are required");
        require(c.bookingDate() != null, where + "columns.bookingDate is required");
        require(!c.description().isEmpty(), where + "columns.description needs at least one column");
        boolean signed = c.amount() != null;
        boolean split = c.debit() != null || c.credit() != null;
        require(signed != split, where + "map either columns.amount or columns.debit + columns.credit");
        require(!split || (c.debit() != null && c.credit() != null), where + "columns.debit and columns.credit go together");
        require((currency == null) != (c.currency() == null), where + "set exactly one of currency or columns.currency");
        require((c.status() == null) == c.pendingValues().isEmpty(),
                where + "columns.status and columns.pendingValues go together");
        if (currency != null) {
            Currency.getInstance(currency);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    public CsvProfile withId(String newId) {
        return new CsvProfile(newId, name, encoding, delimiter, dateFormat, valueDateFormat, decimalSeparator,
                groupingSeparator, currency, ibanInPreamble, columns);
    }

    /** A copy that also maps a bank reference column (used by tests; real formats declare it in YAML). */
    public CsvProfile withReference(String referenceColumn) {
        var c = columns;
        return new CsvProfile(id, name, encoding, delimiter, dateFormat, valueDateFormat, decimalSeparator,
                groupingSeparator, currency, ibanInPreamble, new Columns(c.bookingDate(), c.valueDate(), c.amount(),
                        c.debit(), c.credit(), c.currency(), c.description(), referenceColumn, c.accountIban(),
                        c.status(), c.pendingValues()));
    }

    public Charset charset() {
        return Charset.forName(encoding);
    }

    public char delimiterChar() {
        return delimiter.charAt(0);
    }
}
