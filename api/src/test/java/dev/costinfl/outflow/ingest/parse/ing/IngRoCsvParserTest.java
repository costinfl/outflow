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
 * Golden test on a synthetic ING Home'Bank export in the exact layout of a real one (page chrome inside records,
 * wrapped details, a standing order reusing its reference, Round Ups). Expected numbers come from the generator
 * (see samples/synthetic/README.md), not from this parser. A real anonymized export gets its own golden test once it is
 * re-anonymized.
 */
class IngRoCsvParserTest {

    static final Path FILE = Path.of("..", "samples", "synthetic", "ing-ro-2026-q1.csv");
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
        assertThat(golden.rows()).hasSize(193);
        assertThat(golden.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a < 0).sum()).isEqualTo(-2_335_532L);
        assertThat(golden.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a > 0).sum()).isEqualTo(2_553_247L);
        assertThat(golden.periodFrom()).contains(LocalDate.of(2026, 1, 2));
        assertThat(golden.periodTo()).contains(LocalDate.of(2026, 3, 31));
        assertThat(golden.rows()).extracting(ParsedRow::currency).containsOnly("RON");
        assertThat(golden.accountHint()).isEmpty();
    }

    @Test
    void transactionTypes() {
        Map<String, Long> types = golden.rows().stream()
                .collect(Collectors.groupingBy(r -> r.payload().get("Detalii tranzactie"), Collectors.counting()));

        assertThat(types).isEqualTo(Map.of("Tranzactie Round Up", 91L, "Cumparare POS", 91L, "Plata debit direct", 3L,
                "Transfer Home'Bank", 3L, "Incasare", 3L, "Cumparare POS - stornare", 1L, "Retragere numerar", 1L));
        assertThat(golden.rows().stream().filter(r -> r.payload().get("Detalii tranzactie").equals("Tranzactie Round Up"))
                .mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(-5_202L);
    }

    /** The running balance proves no transaction was lost or misread: every record follows from the one before it. */
    @Test
    void runningBalanceAccountsForEveryTransaction() {
        var rows = golden.rows(); // newest first, as in the file
        for (int i = 0; i < rows.size() - 1; i++) {
            assertThat(balance(rows.get(i))).as("row %d", i + 1).isEqualTo(balance(rows.get(i + 1)) + rows.get(i).amountMinor());
        }
        assertThat(balance(rows.getLast())).isEqualTo(493_057);
        assertThat(balance(rows.getFirst())).isEqualTo(717_715);
    }

    @Test
    void pageChromeNeverLeaksIntoTransactions() {
        assertThat(golden.rows()).allSatisfy(r -> {
            assertThat(r.description()).doesNotContain("Titular cont", "ING Bank N.V.", "Sucursala");
            assertThat(r.payload().values()).noneMatch(v -> v.contains("Sef Serviciu") || v.contains("Jane Doe"));
        });
    }

    @Test
    void detailsCounterpartiesAndDates() {
        ParsedRow roundUp = golden.rows().getFirst();
        assertThat(roundUp.bookingDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(roundUp.amountMinor()).isEqualTo(-50);
        assertThat(roundUp.counterparty()).contains("ING Round Up");
        assertThat(roundUp.description()).isEqualTo("Tranzactie Round Up · Suma tranzactiei: 18.5 RON · la STARBUCKS AFI · ref 40192");

        ParsedRow pos = golden.rows().get(1);
        assertThat(pos.counterparty()).contains("STARBUCKS AFI"); // "  RO  BUCURESTI" stripped
        assertThat(pos.valueDate()).contains(LocalDate.of(2026, 4, 1));
        assertThat(pos.payload()).containsEntry("Numar card", "**** 4412").containsEntry("Balanta", "7.177,65");

        var incoming = golden.rows().stream().filter(r -> r.payload().get("Detalii tranzactie").equals("Incasare")).findFirst().orElseThrow();
        assertThat(incoming.amountMinor()).isEqualTo(850_000);
        assertThat(incoming.counterparty()).contains("ACME SOFTWARE SRL");

        var refund = golden.rows().stream().filter(r -> r.payload().get("Detalii tranzactie").endsWith("stornare")).findFirst().orElseThrow();
        assertThat(refund.amountMinor()).isEqualTo(3_247);
        assertThat(refund.counterparty()).contains("MOL 91151 Rasnov");
    }

    @Test
    void wrappedDetailLinesAreJoined() {
        assertThat(golden.rows()).filteredOn(r -> r.payload().get("Detalii tranzactie").equals("Plata debit direct"))
                .hasSize(3).allSatisfy(r -> assertThat(r.payload().get("Detalii")).isEqualTo("37741995 60439508"));
        assertThat(golden.rows()).noneSatisfy(r -> assertThat(r.payload().keySet()).anyMatch(k -> k.matches("\\d.*")));
    }

    @Test
    void theMonthlyStandingOrderWithOneReferenceStaysThreeTransactions() {
        assertThat(golden.rows()).filteredOn(r -> "948800001".equals(r.payload().get("Referinta"))).hasSize(3)
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
