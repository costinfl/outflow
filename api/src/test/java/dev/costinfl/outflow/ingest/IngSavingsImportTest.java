package dev.costinfl.outflow.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.FileOutcome.Status;
import dev.costinfl.outflow.insight.InsightService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.24: the ING savings export through the whole upload. Detected without choosing a parser; its monthly interest
 * ("Actualizare dobanda") is income; money moved to and from the current account pairs with the current account's
 * side, so it is neither spending nor income.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IngSavingsImportTest {

    static final Path GOLDEN = Path.of("..", "samples", "synthetic", "ing-ro-economii-2026.csv");

    @Autowired UploadService uploads;
    @Autowired InsightService insights;
    @Autowired JdbcTemplate jdbc;

    long main, savings;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        savings = jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, currency, kind)
                VALUES (1, 1, 'Savings', 'RON', 'SAVINGS') RETURNING id""", Long.class);
    }

    @Test
    void interestIsIncomeAndTransfersPairWithTheCurrentAccount() throws Exception {
        var up = uploads.importOne("economii.csv", Files.readAllBytes(GOLDEN), Optional.of(savings), Optional.empty());
        assertThat(up.outcome().status()).isEqualTo(Status.IMPORTED);
        assertThat(up.outcome().parserId()).isEqualTo("ing-ro-csv-v1");
        assertThat(up.outcome().newTransactions()).isEqualTo(8);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT c.code FROM transaction t JOIN category c ON c.id = t.category_id
                WHERE t.description_raw LIKE 'Actualizare dobanda%'""", String.class)).containsExactly("INCOME");

        // The current account's side of October's moves: 5,000 to savings on the 26th, 13,500 back on the 20th.
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-10-20,INCASARE DIN CONT ECONOMII,13500.00,RON\r\n"
                + "2026-10-26,TRANSFER CATRE CONT ECONOMII,-5000.00,RON\r\n";
        uploads.importOne("main.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_pair", Long.class)).isEqualTo(2);
        var october = insights.month(YearMonth.of(2026, 10), "RON");
        assertThat(october.incomeMinor()).isEqualTo(3_401); // the interest only
        assertThat(october.spentMinor()).isZero();
    }
}
