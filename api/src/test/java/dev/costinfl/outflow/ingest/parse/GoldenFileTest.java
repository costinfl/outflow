package dev.costinfl.outflow.ingest.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser;
import dev.costinfl.outflow.ingest.parse.csv.CsvProfileLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * One golden file per parser (plan: "Testing focus"). Expected counts and sums were computed independently of this
 * code, by the script that generated the synthetic samples; see samples/synthetic/README.md.
 */
class GoldenFileTest {

    static final Path SAMPLES = Path.of("..", "samples", "synthetic");

    static StatementParser parser(String resource) throws IOException {
        try (InputStream in = GoldenFileTest.class.getResourceAsStream(resource)) {
            return new ConfigurableCsvParser(CsvProfileLoader.load(in, resource));
        }
    }

    static ParsedStatement parse(StatementParser parser, String file) throws IOException {
        try (InputStream in = Files.newInputStream(SAMPLES.resolve(file))) {
            return parser.parse(in);
        }
    }

    @Test
    void genericJanToMar() throws IOException {
        var s = parse(parser("/parsers/generic-csv-v1.yml"), "generic-2026-01-to-03.csv");

        assertThat(s.parserId()).isEqualTo("generic-csv-v1");
        assertThat(s.rows()).hasSize(63);
        assertThat(s.rows().stream().mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(912_726);
        assertThat(s.periodFrom()).contains(LocalDate.of(2026, 1, 1));
        assertThat(s.periodTo()).contains(LocalDate.of(2026, 3, 26));
        assertThat(s.accountHint()).isEmpty();
        assertThat(s.rows()).extracting(ParsedRow::currency).containsOnly("RON");

        var first = s.rows().getFirst();
        assertThat(first.rowNo()).isEqualTo(1);
        assertThat(first.description()).isEqualTo("PLATA RENT PROPRIETAR IANUARIE");
        assertThat(first.amountMinor()).isEqualTo(-250_000);
        assertThat(first.payload()).containsExactly(
                java.util.Map.entry("Date", "2026-01-01"),
                java.util.Map.entry("Description", "PLATA RENT PROPRIETAR IANUARIE"),
                java.util.Map.entry("Amount", "-2500.00"),
                java.util.Map.entry("Currency", "RON"));
        assertThat(s.rows()).extracting(ParsedRow::rowNo).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 63).boxed().toList());
    }

    @Test
    void genericFebToApr() throws IOException {
        var s = parse(parser("/parsers/generic-csv-v1.yml"), "generic-2026-02-to-04.csv");

        assertThat(s.rows()).hasSize(63);
        assertThat(s.rows().stream().mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(953_449);
        assertThat(s.periodFrom()).contains(LocalDate.of(2026, 2, 1));
        assertThat(s.periodTo()).contains(LocalDate.of(2026, 4, 26));
    }

    @Test
    void genericKeepsLegitimateIdenticalRows() throws IOException {
        var s = parse(parser("/parsers/generic-csv-v1.yml"), "generic-2026-01-to-03.csv");

        assertThat(s.rows()).filteredOn(r -> r.bookingDate().equals(LocalDate.of(2026, 1, 12))
                        && r.description().contains("STARBUCKS"))
                .hasSize(2)
                .extracting(ParsedRow::amountMinor).containsOnly(-1850L);
    }

    @Test
    void romanianStyleWindows1250DebitCreditPreambleIban() throws IOException {
        var s = parse(parser("/test-parsers/ro-style-csv-v1.yml"), "ro-style-2026-02.csv");

        assertThat(s.rows()).hasSize(21);
        assertThat(s.rows().stream().mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(306_308);
        assertThat(s.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a < 0).sum()).isEqualTo(-543_692);
        assertThat(s.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a > 0).sum()).isEqualTo(850_000);
        assertThat(s.periodFrom()).contains(LocalDate.of(2026, 2, 1));
        assertThat(s.periodTo()).contains(LocalDate.of(2026, 2, 26));

        assertThat(s.accountHint()).hasValueSatisfying(hint -> {
            assertThat(hint.iban().value()).isEqualTo("RO49AAAA1B31007593840000");
            assertThat(hint.currency()).contains("RON");
        });

        var kaufland = s.rows().get(1);
        assertThat(kaufland.description()).startsWith("Plată POS KAUFLAND BUCUREŞTI");
        assertThat(kaufland.bookingDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(kaufland.valueDate()).contains(LocalDate.of(2026, 2, 3));
        assertThat(kaufland.amountMinor()).isEqualTo(-35_696);
        assertThat(s.rows().getFirst().amountMinor()).isEqualTo(-250_000); // "2.500,00" in the debit column
        assertThat(kaufland.reference()).isEqualTo(Optional.empty());
    }
}
