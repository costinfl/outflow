package dev.costinfl.outflow.ingest.parse.ing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The user's real ING Home'Bank export (anonymized), 21 months. Expected values were computed independently from the
 * CSV with a separate script (samples/synthetic/README.md, "Real ING export"), not with this parser.
 */
class IngRealSampleTest {

    static final Path FILE = Path.of("..", "samples", "ING Bank Romania", "Tranzactii_24-09-2026_11-36-40-anonymized.csv");
    static ParsedStatement statement;

    @BeforeAll
    static void parse() throws Exception {
        try (InputStream in = Files.newInputStream(FILE)) {
            statement = new IngRoCsvParser().parse(in);
        }
    }

    @Test
    void everyRecordTotalsAndPeriod() {
        List<ParsedRow> rows = statement.rows();

        assertThat(rows).hasSize(4026);
        assertThat(rows.stream().filter(r -> r.amountMinor() < 0).mapToLong(r -> -r.amountMinor()).sum()).isEqualTo(101_837_589);
        assertThat(rows.stream().filter(r -> r.amountMinor() > 0).mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(102_480_553);
        assertThat(statement.periodFrom()).contains(LocalDate.of(2025, 1, 1));
        assertThat(statement.periodTo()).contains(LocalDate.of(2026, 9, 24));
        // "August" is capitalised in real exports, unlike every other month.
        assertThat(rows.stream().filter(r -> r.bookingDate().getMonthValue() == 8)).hasSize(335);
    }

    /**
     * The running balance proves nothing was lost or misread. The bank prints two same-period balances of July 2025 in
     * the other order: two neighbouring breaks that cancel out. Over the whole export the balance adds up exactly.
     */
    @Test
    void runningBalanceHoldsExceptOneSwappedPairThatCancels() {
        List<ParsedRow> rows = statement.rows();
        var breaks = new ArrayList<Long>();
        for (int i = 0; i < rows.size() - 1; i++) {
            long expected = IngRoCsvParserTest.balance(rows.get(i + 1)) + rows.get(i).amountMinor();
            long actual = IngRoCsvParserTest.balance(rows.get(i));
            if (actual != expected) {
                breaks.add(actual - expected);
            }
        }
        assertThat(breaks).containsExactly(900L, -900L);

        long movements = rows.subList(0, rows.size() - 1).stream().mapToLong(ParsedRow::amountMinor).sum();
        assertThat(IngRoCsvParserTest.balance(rows.getFirst()))
                .isEqualTo(IngRoCsvParserTest.balance(rows.getLast()) + movements)
                .isEqualTo(674_527);
    }

    @Test
    void anonymizationLeftNoFreeTextNotes() {
        for (ParsedRow r : statement.rows()) {
            String memo = r.payload().getOrDefault("Detalii", "");
            assertThat(memo).as("row %d", r.rowNo()).matches("|NOTE_\\d+.*|Suma tranzactiei: [0-9.,]+ [A-Z]{3}");
        }
    }
}
