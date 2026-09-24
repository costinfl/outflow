package dev.costinfl.outflow.ingest.parse.ing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Golden test on a real, anonymized ING Home'Bank export. Expected numbers were computed independently from the raw CSV
 * (Python csv module), not with this parser.
 */
class IngRoCsvParserTest {

    static final Path FILE = Path.of("..", "samples", "ING Bank Romania", "Tranzactii_24-09-2026_11-36-40-anonymized.csv");
    static ParsedStatement golden;

    @BeforeAll
    static void parseGolden() throws Exception {
        try (InputStream in = Files.newInputStream(FILE)) {
            golden = new IngRoCsvParser().parse(in);
        }
    }

    static long balance(ParsedRow r) {
        String b = r.payload().get("Balanta").replace(".", "").replace(",", "");
        return Long.parseLong(b);
    }

    @Test
    void rowCountSumsAndPeriod() {
        assertThat(golden.rows()).hasSize(4026);
        assertThat(golden.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a < 0).sum()).isEqualTo(-101_837_589L);
        assertThat(golden.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a > 0).sum()).isEqualTo(102_480_553L);
        assertThat(golden.periodFrom()).contains(LocalDate.of(2025, 1, 1));
        assertThat(golden.periodTo()).contains(LocalDate.of(2026, 9, 24));
        assertThat(golden.rows()).extracting(ParsedRow::currency).containsOnly("RON");
        assertThat(golden.accountHint()).isEmpty();
    }

    @Test
    void transactionTypes() {
        Map<String, Long> types = golden.rows().stream()
                .collect(Collectors.groupingBy(r -> r.payload().get("Detalii tranzactie"), Collectors.counting()));

        assertThat(types).containsEntry("Tranzactie Round Up", 1562L).containsEntry("Cumparare POS", 1548L)
                .containsEntry("Transfer Home'Bank", 419L).containsEntry("Incasare", 401L).hasSize(16);
        assertThat(golden.rows().stream().filter(r -> r.payload().get("Detalii tranzactie").equals("Tranzactie Round Up"))
                .mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(-2_525_565L);
    }

    /**
     * The running balance proves no transaction was lost or misread: newest balance = oldest balance + every movement
     * after the oldest. Locally ING lists two neighbours of 22–23 July 2025 out of balance order; those two breaks
     * cancel out (+9.00 / −9.00), which is all that is allowed.
     */
    @Test
    void runningBalanceAccountsForEveryTransaction() {
        var rows = golden.rows(); // newest first, as in the file
        long movementsAfterOldest = rows.subList(0, rows.size() - 1).stream().mapToLong(ParsedRow::amountMinor).sum();
        assertThat(balance(rows.getFirst())).isEqualTo(balance(rows.getLast()) + movementsAfterOldest);

        long breaks = 0;
        long drift = 0;
        for (int i = 0; i < rows.size() - 1; i++) {
            long diff = balance(rows.get(i)) - (balance(rows.get(i + 1)) + rows.get(i).amountMinor());
            if (diff != 0) {
                breaks++;
                drift += diff;
            }
        }
        assertThat(breaks).isEqualTo(2);
        assertThat(drift).isZero();
    }

    @Test
    void pageChromeNeverLeaksIntoTransactions() {
        assertThat(golden.rows()).allSatisfy(r -> {
            assertThat(r.description()).doesNotContain("Titular cont", "ING Bank N.V.", "Sucursala");
            assertThat(r.payload().values()).noneMatch(v -> v.contains("Şef Serviciu"));
        });
    }

    @Test
    void detailsCounterpartiesAndDates() {
        ParsedRow newest = golden.rows().getFirst();
        assertThat(newest.bookingDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(newest.valueDate()).contains(LocalDate.of(2026, 9, 24));
        assertThat(newest.amountMinor()).isEqualTo(-3952);
        assertThat(newest.counterparty()).contains("1MINUTE HERMES (B) C1"); // "  RO  BUCURESTI" stripped
        assertThat(newest.payload().get("Numar card")).matches("\\*{4} \\d{4}"); // digits are anonymizer fakes
        assertThat(newest.payload()).containsEntry("Balanta", "6.745,27");

        var roundUp = golden.rows().get(1);
        assertThat(roundUp.counterparty()).contains("ING Round Up");
        assertThat(roundUp.description()).isEqualTo("Tranzactie Round Up · Suma tranzactiei: 88.1 RON · la EASYBOX YAHOOBEST · ref 34880");

        var incoming = golden.rows().stream().filter(r -> r.payload().get("Detalii tranzactie").equals("Incasare")).findFirst().orElseThrow();
        assertThat(incoming.amountMinor()).isPositive();
        assertThat(incoming.counterparty()).hasValueSatisfying(c -> assertThat(c).matches("PERSON_\\d+"));
    }

    @Test
    void wrappedDetailLinesAreJoined() {
        assertThat(golden.rows()).anySatisfy(r -> assertThat(r.payload().get("Detalii")).isEqualTo("37741995 60439508"));
        assertThat(golden.rows()).noneSatisfy(r -> assertThat(r.payload().keySet()).anyMatch(k -> k.matches("\\d.*")));
    }

    @Test
    void theMonthlyStandingOrderWithOneReferenceStaysFourTransactions() {
        assertThat(golden.rows()).filteredOn(r -> "948872823".equals(r.payload().get("Referinta"))).hasSize(4)
                .allSatisfy(r -> assertThat(r.reference()).isEmpty())
                .extracting(ParsedRow::bookingDate).doesNotHaveDuplicates();
    }

    // --- synthetic edge cases ---------------------------------------------------------------------------------

    static final String HEAD = "Titular cont: PERSON_1,,,,,,,\nData,,,Detalii tranzactie,Debit,,Credit,Balanta\n";

    static ParsedStatement parse(String csv) throws Exception {
        return new IngRoCsvParser().parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aPageBreakInsideARecordKeepsItsDetails() throws Exception {
        var s = parse(HEAD
                + "05 martie 2026,,,Cumparare POS,\"1.234,56\",,,\"100,00\"\n"
                + ",,,Data finalizarii (decontarii): 06-03-2026,,,,\n"
                + ",Jane Doe,,,,John Roe,,\n"
                + ",,ING Bank N.V. Amsterdam,,,ING Bank N.V. Amsterdam,,\n"
                + HEAD
                + ",,,Tranzactie la:LIDL 123  RO  CLUJ,,,,\n"
                + "04 martie 2026,,,Incasare,,,\"50,00\",\"1.334,56\"\n"
                + ",,,Ordonator:ACME SRL,,,,\n");

        assertThat(s.rows()).hasSize(2);
        assertThat(s.rows().getFirst().amountMinor()).isEqualTo(-123_456);
        assertThat(s.rows().getFirst().counterparty()).contains("LIDL 123");
        assertThat(s.rows().getFirst().valueDate()).contains(LocalDate.of(2026, 3, 6));
        assertThat(s.rows().get(1).counterparty()).contains("ACME SRL");
        assertThat(s.rows().get(1).amountMinor()).isEqualTo(5_000);
    }

    @Test
    void anythingUnexpectedFailsTheFileInsteadOfBeingSkipped() {
        assertThatThrownBy(() -> parse(HEAD + "05 martie 2026,,,Cumparare POS,\"1,00\",,\"2,00\",\"3,00\"\n"))
                .isInstanceOf(StatementParseException.class).hasMessageContaining("exactly one of Debit or Credit");
        assertThatThrownBy(() -> parse(HEAD + "Sold final,,,,,,,\"3,00\"\n"))
                .hasMessageContaining("unexpected content");
        assertThatThrownBy(() -> parse("Data,Descriere,Suma\n"))
                .hasMessageContaining("column header not found");
    }

    @Test
    void detection() throws Exception {
        var parser = new IngRoCsvParser();
        assertThat(parser.detect(FileSample.of("t.csv", Files.readAllBytes(FILE))).value()).isEqualTo(1.0);
        assertThat(parser.detect(FileSample.of("t.csv", "Date,Description,Amount,Currency\n".getBytes())).value()).isZero();
        assertThat(IngRoCsvParser.parseDate("31 februarie 2026")).isEqualTo(Optional.empty());
        assertThat(IngRoCsvParser.parseDate("1 mai 2026")).contains(LocalDate.of(2026, 5, 1));
    }
}
