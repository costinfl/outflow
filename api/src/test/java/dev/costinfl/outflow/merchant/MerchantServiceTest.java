package dev.costinfl.outflow.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP2.1: merchants are assigned on upload, stable, and recomputable from raw descriptions. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MerchantServiceTest {

    /** Merchant key → transactions, for the Jan–Apr union (84 rows). The seeded ENEL alias shortens "ENEL ENERGIE". */
    static final Map<String, Long> EXPECTED = Map.ofEntries(
            Map.entry("BOLT", 16L), Map.entry("CONT ECONOMII", 4L), Map.entry("ENEL", 4L), Map.entry("KAUFLAND", 16L),
            Map.entry("LIDL", 16L), Map.entry("NETFLIX", 4L), Map.entry("ORANGE", 4L), Map.entry("RENT PROPRIETAR", 4L),
            Map.entry("SALARIU ACME SRL", 4L), Map.entry("SPOTIFY", 4L), Map.entry("STARBUCKS", 8L));

    @Autowired UploadService uploads;
    @Autowired MerchantService merchants;
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        for (String file : new String[] {"generic-2026-01-to-03.csv", "generic-2026-02-to-04.csv"}) {
            uploads.importOne(file, ImportFixtures.sample(file), Optional.of(account), Optional.empty());
        }
    }

    @Test
    void everyUploadedTransactionGetsItsMerchant() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE merchant_id IS NULL", Long.class)).isZero();
        assertThat(merchants.transactionCountsByKey()).isEqualTo(EXPECTED);
        assertThat(jdbc.queryForObject("SELECT display_name FROM merchant WHERE key = 'RENT PROPRIETAR'", String.class))
                .isEqualTo("Rent Proprietar");
    }

    @Test
    void recomputingFromRawChangesNothing() {
        var before = jdbc.queryForList("SELECT id, merchant_id FROM transaction ORDER BY id");

        assertThat(merchants.reassignAll()).isZero();
        assertThat(jdbc.queryForList("SELECT id, merchant_id FROM transaction ORDER BY id")).isEqualTo(before);
    }

    @Test
    void aUserAliasMovesExactlyTheMatchingTransactions() {
        jdbc.update("INSERT INTO merchant_alias (match_type, pattern, merchant_key, source) VALUES ('EXACT', 'RENT PROPRIETAR', 'LANDLORD', 'USER')");

        int moved = merchants.reassignAll();

        assertThat(moved).isEqualTo(4);
        var counts = merchants.transactionCountsByKey();
        assertThat(counts).containsEntry("LANDLORD", 4L).doesNotContainKey("RENT PROPRIETAR");
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(84);
    }

    @Test
    void merchantAssignmentIsRecomputableFromRawRowsAlone() {
        jdbc.update("UPDATE transaction SET merchant_id = NULL");

        assertThat(merchants.assignMissing()).isEqualTo(84);
        assertThat(merchants.transactionCountsByKey()).isEqualTo(EXPECTED);
    }

    @Test
    void seededAliasesAreLoaded() {
        assertThat(merchants.normalizer().key("CUMPARARE POS AMZN MKTP DE*2B4XY7Z")).isEqualTo("AMAZON");
        assertThat(merchants.normalizer().key("MEGA IMAGE 0123 BUCURESTI RO")).isEqualTo("MEGA IMAGE");
    }
}
