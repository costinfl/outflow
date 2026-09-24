package dev.costinfl.outflow.ingest.parse.csv;

import dev.costinfl.outflow.ingest.parse.AccountHint;
import dev.costinfl.outflow.ingest.parse.DetectionScore;
import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.ingest.parse.MoneyParser;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** A {@link StatementParser} driven entirely by a {@link CsvProfile}; one instance per profile. */
public final class ConfigurableCsvParser implements StatementParser {

    private final CsvProfile profile;
    private final MoneyParser money;
    private final DateTimeFormatter bookingDates;
    private final DateTimeFormatter valueDates;

    public ConfigurableCsvParser(CsvProfile profile) {
        this.profile = profile;
        this.money = new MoneyParser(
                profile.decimalSeparator().charAt(0),
                profile.groupingSeparator() == null ? null : profile.groupingSeparator().charAt(0));
        this.bookingDates = strictDates(profile.dateFormat());
        this.valueDates = strictDates(profile.valueDateFormat());
    }

    private static DateTimeFormatter strictDates(String pattern) {
        // 'uuuu' instead of 'yyyy' so STRICT resolution works without an era.
        return DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu").replace("yy", "uu"), Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    public CsvProfile profile() {
        return profile;
    }

    @Override
    public String id() {
        return profile.id();
    }

    @Override
    public String displayName() {
        return profile.name() == null ? profile.id() : profile.name();
    }

    @Override
    public DetectionScore detect(FileSample sample) {
        List<List<String>> records;
        try {
            // The sample may end mid-record; that only affects the last line, which detection does not need.
            records = CsvReader.read(new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(sample.head()),
                    profile.charset().newDecoder().onMalformedInput(CodingErrorAction.REPORT))), profile.delimiterChar());
        } catch (CharacterCodingException e) {
            return DetectionScore.none("not " + profile.encoding());
        } catch (IOException e) {
            return DetectionScore.none("not readable as CSV with '" + profile.delimiter() + "'");
        }
        var header = findHeader(records);
        if (header.isEmpty()) {
            return DetectionScore.none("header columns not found");
        }
        var layout = header.get();
        int dataIndex = layout.index + 1;
        while (dataIndex < records.size() && CsvReader.isBlank(records.get(dataIndex))) {
            dataIndex++;
        }
        // Skip the last record: the sample may have cut it off.
        if (dataIndex >= records.size() - 1) {
            return new DetectionScore(0.8, "header matches; no complete data row in sample");
        }
        try {
            parseRow(layout, records.get(dataIndex), 1);
            return new DetectionScore(1.0, "header matches and first row parses");
        } catch (StatementParseException e) {
            return new DetectionScore(0.5, "header matches but first row does not parse: " + e.getMessage());
        }
    }

    @Override
    public ParsedStatement parse(InputStream in) throws IOException {
        List<List<String>> records;
        try {
            records = CsvReader.read(new BufferedReader(new InputStreamReader(
                    in, profile.charset().newDecoder().onMalformedInput(CodingErrorAction.REPORT))), profile.delimiterChar());
        } catch (CharacterCodingException e) {
            throw new StatementParseException("File is not valid " + profile.encoding(), e);
        }
        var layout = findHeader(records).orElseThrow(() -> new StatementParseException(
                "Header not found; expected columns " + profile.columns().required()
                        + " (delimiter '" + profile.delimiter() + "', encoding " + profile.encoding() + ")"));

        var rows = new ArrayList<ParsedRow>();
        for (int i = layout.index + 1; i < records.size(); i++) {
            var record = records.get(i);
            if (CsvReader.isBlank(record)) {
                continue;
            }
            rows.add(parseRow(layout, record, rows.size() + 1));
        }
        return ParsedStatement.of(id(), accountHint(records, layout, rows), rows);
    }

    private Optional<AccountHint> accountHint(List<List<String>> records, Layout layout, List<ParsedRow> rows) {
        Optional<String> currency = rows.stream().map(ParsedRow::currency).distinct().count() == 1
                ? Optional.of(rows.getFirst().currency())
                : Optional.ofNullable(profile.currency());
        String ibanColumn = profile.columns().accountIban();
        if (ibanColumn != null) {
            var ibans = rows.stream()
                    .map(r -> r.payload().get(layout.names.get(layout.positions.get(key(ibanColumn)))))
                    .filter(v -> v != null && !v.isBlank())
                    .map(v -> Iban.parse(v).orElseThrow(() -> new StatementParseException(
                            "Column '" + ibanColumn + "' holds a value that is not a valid IBAN")))
                    .distinct()
                    .toList();
            if (ibans.size() > 1) {
                throw new StatementParseException("File mixes " + ibans.size() + " accounts; upload one account per file");
            }
            return ibans.stream().findFirst().map(iban -> new AccountHint(iban, currency));
        }
        if (profile.ibanInPreamble()) {
            return records.subList(0, layout.index).stream()
                    .map(r -> String.join(" ", r))
                    .map(Iban::find)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .map(iban -> new AccountHint(iban, currency));
        }
        return Optional.empty();
    }

    /** Header position plus, per mapped column (normalized), its index in the record. */
    private record Layout(int index, List<String> names, Map<String, Integer> positions) {}

    private Optional<Layout> findHeader(List<List<String>> records) {
        var wanted = profile.columns().required().stream().map(ConfigurableCsvParser::key).toList();
        for (int i = 0; i < records.size(); i++) {
            var cells = records.get(i);
            var positions = new HashMap<String, Integer>();
            for (int j = 0; j < cells.size(); j++) {
                positions.putIfAbsent(key(cells.get(j)), j);
            }
            if (positions.keySet().containsAll(wanted)) {
                return Optional.of(new Layout(i, uniqueNames(cells), positions));
            }
        }
        return Optional.empty();
    }

    /** Header cells as payload keys; blank or repeated names get a positional suffix so no cell is lost. */
    private static List<String> uniqueNames(List<String> header) {
        var seen = new HashMap<String, Integer>();
        var names = new ArrayList<String>();
        for (int j = 0; j < header.size(); j++) {
            String base = header.get(j).strip();
            if (base.isEmpty()) {
                base = "column_" + (j + 1);
            }
            int n = seen.merge(base, 1, Integer::sum);
            names.add(n == 1 ? base : base + "_" + n);
        }
        return names;
    }

    private static String key(String headerName) {
        return headerName.strip().toLowerCase(Locale.ROOT);
    }

    private ParsedRow parseRow(Layout layout, List<String> record, int rowNo) {
        if (record.size() > layout.names.size() && !record.subList(layout.names.size(), record.size())
                .stream().allMatch(String::isBlank)) {
            throw StatementParseException.atRow(rowNo, "has " + record.size() + " cells, header has " + layout.names.size());
        }
        var payload = new LinkedHashMap<String, String>();
        for (int j = 0; j < layout.names.size(); j++) {
            payload.put(layout.names.get(j), j < record.size() ? record.get(j) : "");
        }
        var c = profile.columns();
        try {
            String currencyCode = c.currency() != null ? cell(layout, record, c.currency()).strip() : profile.currency();
            Currency currency = currency(currencyCode);
            return new ParsedRow(
                    rowNo,
                    payload,
                    date(cell(layout, record, c.bookingDate()), bookingDates, "booking date"),
                    optional(layout, record, c.valueDate()).map(v -> date(v, valueDates, "value date")),
                    amount(layout, record, currency),
                    currency.getCurrencyCode(),
                    c.description().stream()
                            .map(col -> cell(layout, record, col).strip())
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.joining(" ")),
                    optional(layout, record, c.reference()));
        } catch (IllegalArgumentException e) {
            throw StatementParseException.atRow(rowNo, e.getMessage());
        }
    }

    private long amount(Layout layout, List<String> record, Currency currency) {
        var c = profile.columns();
        if (c.amount() != null) {
            return money.toMinor(cell(layout, record, c.amount()), currency);
        }
        var debit = optional(layout, record, c.debit());
        var credit = optional(layout, record, c.credit());
        if (debit.isPresent() == credit.isPresent()) {
            throw new IllegalArgumentException("needs exactly one of debit or credit");
        }
        return debit.isPresent()
                ? -Math.abs(money.toMinor(debit.get(), currency))
                : Math.abs(money.toMinor(credit.get(), currency));
    }

    private static Currency currency(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("invalid currency '" + code + "'");
        }
        return Currency.getInstance(code);
    }

    private static LocalDate date(String text, DateTimeFormatter format, String what) {
        try {
            return LocalDate.parse(text.strip(), format);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid " + what + " '" + text + "'");
        }
    }

    private static String cell(Layout layout, List<String> record, String column) {
        int j = layout.positions.get(key(column));
        return j < record.size() ? record.get(j) : "";
    }

    private static Optional<String> optional(Layout layout, List<String> record, String column) {
        if (column == null) {
            return Optional.empty();
        }
        String v = cell(layout, record, column);
        return v.isBlank() ? Optional.empty() : Optional.of(v.strip());
    }
}
