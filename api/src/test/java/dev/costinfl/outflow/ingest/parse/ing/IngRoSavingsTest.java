package dev.costinfl.outflow.ingest.parse.ing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CP6.24: the ING savings account export ("cont de economii"), whose header puts Debit and Credit one column later
 * than the current account's and has no running balance: {@code Data,,,Detalii tranzactie,,Debit,Credit}. Golden file
 * {@code samples/synthetic/ing-ro-economii-2026.csv} (fabricated, in the layout of the user's real export; values in
 * samples/synthetic/README.md).
 */
class IngRoSavingsTest {

    static final Path FILE = Path.of("..", "samples", "synthetic", "ing-ro-economii-2026.csv");
    static ParsedStatement golden;

    @BeforeAll
    static void parseGolden() throws Exception {
        try (InputStream in = Files.newInputStream(FILE)) {
            golden = new IngRoCsvParser().parse(in);
        }
    }

    @Test
    void isDetectedAsAnIngExport() throws Exception {
        var score = new IngRoCsvParser().detect(FileSample.of("economii.csv", Files.readAllBytes(FILE)));
        assertThat(score.value()).isEqualTo(1.0);
    }

    @Test
    void rowsSumsAndPeriod() {
        assertThat(golden.rows()).hasSize(8);
        assertThat(golden.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a < 0).sum()).isEqualTo(-1_750_000L);
        assertThat(golden.rows().stream().mapToLong(ParsedRow::amountMinor).filter(a -> a > 0).sum()).isEqualTo(1_009_576L);
        assertThat(golden.periodFrom()).contains(LocalDate.of(2026, 9, 7));
        assertThat(golden.periodTo()).contains(LocalDate.of(2026, 11, 4));
        assertThat(golden.rows()).allSatisfy(r -> assertThat(r.payload()).doesNotContainKey("Balanta"));
    }

    @Test
    void eachEntryKeepsItsTypeDetailsAndDirection() {
        List<ParsedRow> rows = golden.rows();
        var transfer = rows.getFirst();
        assertThat(transfer.amountMinor()).isEqualTo(-300_000);
        assertThat(transfer.description()).startsWith("Transfer Home'Bank: Jane Doe");
        assertThat(transfer.payload()).containsEntry("In contul", "RO22ANON0000000000000101");

        var interest = rows.get(1);
        assertThat(interest.payload()).containsEntry("Detalii tranzactie", "Actualizare dobanda")
                .containsEntry("Principal", "34,01").containsEntry("Impozit pe dobanda", "3,78");
        assertThat(interest.amountMinor()).isEqualTo(3_401);
        assertThat(interest.valueDate()).contains(LocalDate.of(2026, 10, 31));

        var bonus = rows.stream().filter(r -> r.amountMinor() == 2_090).findFirst().orElseThrow();
        assertThat(bonus.counterparty()).contains("ING BANK NV");
        assertThat(bonus.payload()).containsEntry("Mesaj", "bonus pentru economii").containsEntry("Referinta", "9001");
    }

    @Test
    void theCurrentAccountLayoutStillReadsItsBalance() {
        var current = IngRoCsvParser.layout(List.of("Data", "", "", "Detalii tranzactie", "Debit", "", "Credit", "Balanta"));
        assertThat(current).contains(new IngRoCsvParser.Layout(4, 6, 7));
        var savings = IngRoCsvParser.layout(List.of("Data", "", "", "Detalii tranzactie", "", "Debit", "Credit"));
        assertThat(savings).contains(new IngRoCsvParser.Layout(5, 6, -1));
        assertThat(IngRoCsvParser.layout(List.of("Data", "", "", "Detalii tranzactie", "Suma"))).isEmpty();
    }
}
