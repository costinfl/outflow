package dev.costinfl.outflow.ingest.parse.ing;

import dev.costinfl.outflow.ingest.parse.DetectionScore;
import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.MoneyParser;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import dev.costinfl.outflow.ingest.parse.csv.CsvReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ING Bank Romania Home'Bank "Tranzactii" CSV export. One transaction spans several lines: a first line with the date
 * ("24 septembrie 2026"), the type, Debit or Credit and the running Balanta, then detail lines ("Key:Value") in the
 * fourth column. Every page repeats a holder line, the column header and a signature block; long details wrap onto a
 * line without a key. The export names no IBAN for the account itself, so the user picks the account on upload.
 *
 * <p>ING's "Referinta" is not unique per transaction (a standing order reuses it every month), so it is part of the
 * description, never the identity reference.
 */
public final class IngRoCsvParser implements StatementParser {

    public static final String ID = "ing-ro-csv-v1";
    static final List<String> HEADER = List.of("Data", "", "", "Detalii tranzactie", "Debit", "", "Credit", "Balanta");
    static final Currency RON = Currency.getInstance("RON");

    private static final Pattern DATE_LINE = Pattern.compile("(\\d{1,2}) (\\p{L}+) (\\d{4})");
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("ianuarie", 1), Map.entry("februarie", 2), Map.entry("martie", 3), Map.entry("aprilie", 4),
            Map.entry("mai", 5), Map.entry("iunie", 6), Map.entry("iulie", 7), Map.entry("august", 8),
            Map.entry("septembrie", 9), Map.entry("octombrie", 10), Map.entry("noiembrie", 11), Map.entry("decembrie", 12));
    /** "Key:Value": the key is words (letters, spaces, parentheses, apostrophes), as in every detail ING writes. */
    private static final Pattern DETAIL = Pattern.compile("([\\p{L}][\\p{L} ()'.-]{0,79}):(.*)", Pattern.DOTALL);
    private static final Pattern POS_LOCATION = Pattern.compile("\\s{2,}");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final MoneyParser money = new MoneyParser(',', '.');

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "ING Bank Romania (Home'Bank CSV)";
    }

    @Override
    public DetectionScore detect(FileSample sample) {
        List<List<String>> records;
        try {
            records = read(new ByteArrayInputStream(sample.head()));
        } catch (IOException | StatementParseException e) {
            return DetectionScore.none("not UTF-8 CSV"); // detection must never throw on foreign input
        }
        boolean header = records.stream().anyMatch(r -> isHeader(r));
        if (!header) {
            return DetectionScore.none("ING header (Data, Detalii tranzactie, Debit, Credit, Balanta) not found");
        }
        boolean dated = records.stream().anyMatch(r -> !r.isEmpty() && parseDate(r.getFirst().strip()).isPresent());
        return dated ? new DetectionScore(1.0, "ING header and a dated transaction line")
                : new DetectionScore(0.8, "ING header; no transaction line in the sample");
    }

    @Override
    public ParsedStatement parse(InputStream in) throws IOException {
        List<List<String>> records = read(in);
        var rows = new ArrayList<ParsedRow>();
        Builder current = null;
        boolean headerSeen = false;
        for (int i = 0; i < records.size(); i++) {
            List<String> r = records.get(i);
            String first = cell(r, 0).strip();
            if (CsvReader.isBlank(r) || first.startsWith("Titular cont")) {
                continue;
            }
            if (isHeader(r)) {
                headerSeen = true;
                continue;
            }
            var date = parseDate(first);
            if (date.isPresent()) {
                if (!headerSeen) {
                    throw new StatementParseException("Line " + (i + 1) + ": transaction before the ING column header");
                }
                if (current != null) {
                    rows.add(current.build(rows.size() + 1));
                }
                current = new Builder(date.get(), first, cell(r, 3).strip(), cell(r, 4).strip(), cell(r, 6).strip(),
                        cell(r, 7).strip());
                continue;
            }
            if (first.isEmpty() && cell(r, 3).isBlank()) {
                continue; // page footer: the bank's signature block
            }
            if (first.isEmpty() && current != null) {
                current.detail(cell(r, 3).strip());
                continue;
            }
            if (!headerSeen) {
                throw new StatementParseException("Not an ING Home'Bank export: column header not found");
            }
            throw new StatementParseException("Line " + (i + 1) + ": unexpected content for an ING export");
        }
        if (!headerSeen) {
            throw new StatementParseException("Not an ING Home'Bank export: column header not found");
        }
        if (current != null) {
            rows.add(current.build(rows.size() + 1));
        }
        return ParsedStatement.of(ID, Optional.empty(), rows);
    }

    private static List<List<String>> read(InputStream in) throws IOException {
        try {
            return CsvReader.read(new BufferedReader(new InputStreamReader(in,
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))), ',');
        } catch (CharacterCodingException e) {
            throw new StatementParseException("File is not valid UTF-8", e);
        }
    }

    private static boolean isHeader(List<String> r) {
        return r.size() >= HEADER.size() && r.subList(0, HEADER.size()).stream().map(String::strip).toList().equals(HEADER);
    }

    private static String cell(List<String> r, int i) {
        return i < r.size() ? r.get(i) : "";
    }

    static Optional<LocalDate> parseDate(String text) {
        Matcher m = DATE_LINE.matcher(text);
        // Month names are lowercase, except that real exports write "August" capitalised.
        String month = m.matches() ? m.group(2).toLowerCase(java.util.Locale.ROOT) : "";
        if (!MONTHS.containsKey(month)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(Integer.parseInt(m.group(3)), MONTHS.get(month), Integer.parseInt(m.group(1))));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /** One transaction while its detail lines are being read. */
    private final class Builder {

        private final LocalDate bookingDate;
        private final String type;
        private final String debit;
        private final String credit;
        private final Map<String, String> payload = new LinkedHashMap<>();
        private final Map<String, String> details = new LinkedHashMap<>();
        private String lastKey;

        Builder(LocalDate bookingDate, String dateText, String type, String debit, String credit, String balance) {
            this.bookingDate = bookingDate;
            this.type = type;
            this.debit = debit;
            this.credit = credit;
            payload.put("Data", dateText);
            payload.put("Detalii tranzactie", type);
            payload.put("Debit", debit);
            payload.put("Credit", credit);
            payload.put("Balanta", balance);
        }

        void detail(String line) {
            Matcher m = DETAIL.matcher(line);
            if (m.matches()) {
                String key = m.group(1).strip();
                String unique = key;
                for (int n = 2; payload.containsKey(unique); n++) {
                    unique = key + "_" + n;
                }
                payload.put(unique, m.group(2).strip());
                details.putIfAbsent(key, m.group(2).strip());
                lastKey = unique;
            } else if (lastKey != null) {
                // A detail too long for one line continues on the next, without a key.
                payload.put(lastKey, (payload.get(lastKey) + " " + line).strip());
                String base = lastKey.replaceFirst("_\\d+$", "");
                if (details.containsKey(base)) {
                    details.put(base, (details.get(base) + " " + line).strip());
                }
            } else {
                payload.put("Detalii tranzactie", (type + " " + line).strip());
            }
        }

        ParsedRow build(int rowNo) {
            if (debit.isEmpty() == credit.isEmpty()) {
                throw StatementParseException.atRow(rowNo, "needs exactly one of Debit or Credit (" + payload.get("Data") + ")");
            }
            long amount;
            try {
                amount = debit.isEmpty() ? Math.abs(money.toMinor(credit, RON)) : -Math.abs(money.toMinor(debit, RON));
            } catch (IllegalArgumentException e) {
                throw StatementParseException.atRow(rowNo, e.getMessage());
            }
            Optional<LocalDate> valueDate = Optional.ofNullable(details.getOrDefault("Data finalizarii (decontarii)", details.get("Data")))
                    .flatMap(IngRoCsvParser::dmy);
            return new ParsedRow(rowNo, payload, bookingDate, valueDate, amount, RON.getCurrencyCode(), description(),
                    Optional.empty(), Optional.of(counterparty()));
        }

        /** Who was paid or who paid; merchant detection works on this. */
        String counterparty() {
            String t = type.toLowerCase(java.util.Locale.ROOT);
            if (t.contains("round up")) {
                return "ING Round Up";
            }
            if (t.startsWith("retragere")) {
                return "Retragere numerar ATM";
            }
            if (details.containsKey("Tranzactie la")) {
                // "PRIMARK 880 BUCHAREST  RO  BUCHAREST": ING appends "  <country>  <city>" with double spaces.
                String[] parts = POS_LOCATION.split(details.get("Tranzactie la"));
                if (parts.length >= 3 && parts[parts.length - 2].matches("[A-Z]{2}")) {
                    return String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 2)).strip();
                }
                return details.get("Tranzactie la");
            }
            for (String key : List.of("Beneficiar", "Ordonator")) {
                if (details.containsKey(key) && !details.get(key).isBlank()) {
                    return details.get(key);
                }
            }
            return type;
        }

        /** Readable and stable across exports: type, counterparty text, payment details, bank reference. */
        String description() {
            var d = new StringBuilder(type);
            for (String key : List.of("Tranzactie la", "Beneficiar", "Ordonator")) {
                if (details.containsKey(key)) {
                    d.append(": ").append(details.get(key));
                    break;
                }
            }
            if (details.containsKey("Detalii")) {
                d.append(" · ").append(details.get("Detalii"));
            }
            if (details.containsKey("Platita la")) {
                d.append(" · la ").append(details.get("Platita la"));
            }
            if (details.containsKey("Referinta")) {
                d.append(" · ref ").append(details.get("Referinta"));
            }
            return d.toString();
        }
    }

    static Optional<LocalDate> dmy(String text) {
        try {
            return Optional.of(LocalDate.parse(text.strip(), DMY));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
