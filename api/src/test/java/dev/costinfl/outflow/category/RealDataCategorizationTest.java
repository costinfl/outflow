package dev.costinfl.outflow.category;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How much of real spending the seeds categorize, on the user's anonymized 21-month ING export. Spending = money out
 * that is not a transfer. Transfers to people (PERSON_n) need the user's answer in the review inbox, so merchants are
 * measured on their own too. Measured before this change: merchants 40.8% of the amount, everything 13.3%.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDataCategorizationTest {

    static final Path FILE = Path.of("..", "samples", "ING Bank Romania", "Tranzactii_24-09-2026_11-36-40-anonymized.csv");

    @Autowired UploadService uploads;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void importRealExport() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "ING");
        uploads.importOne(FILE.getFileName().toString(), Files.readAllBytes(FILE), Optional.of(account), Optional.empty());
    }

    /** Share of spending (by amount) with a category, for merchants only or for everything, in percent. */
    double coverage(boolean merchantsOnly) {
        return jdbc.queryForObject("""
                SELECT 100.0 * coalesce(sum(-t.amount_minor) FILTER (WHERE t.category_id IS NOT NULL), 0) / sum(-t.amount_minor)
                FROM transaction t JOIN merchant m ON m.id = t.merchant_id LEFT JOIN category c ON c.id = t.category_id
                WHERE t.amount_minor < 0 AND coalesce(c.kind, 'SPEND') <> 'TRANSFER' AND t.transfer_state IS NULL
                  AND (NOT ? OR m.key !~ '^PERSON_')""", Double.class, merchantsOnly);
    }

    @Test
    void mostMerchantSpendingIsCategorized() {
        double merchants = coverage(true);
        double all = coverage(false);
        System.out.printf("Real ING export: merchants %.1f%%, all spending %.1f%% categorized%n", merchants, all);

        assertThat(merchants).isGreaterThanOrEqualTo(72.0);
        assertThat(all).isGreaterThanOrEqualTo(22.0);
    }

    @Test
    void insurancePremiumsAreInsurance() {
        var categories = jdbc.queryForList("""
                SELECT DISTINCT c.code FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                JOIN category c ON c.id = t.category_id WHERE m.key LIKE 'ALLIANZ%'""", String.class);
        assertThat(categories).containsExactly("INSURANCE");
    }

    @Test
    void paymentProcessorsNoLongerSwallowTheMerchant() {
        Map<String, Object> keys = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE key IN ('PAYU', 'MOBILPAY', 'NYX', 'MPY', 'EP', 'NETOPIA')) AS processors,
                       count(*) FILTER (WHERE key = 'EMAG') AS emag,
                       count(*) FILTER (WHERE key = 'AMPARCAT') AS amparcat
                FROM merchant""");
        assertThat(keys).containsEntry("processors", 0L).containsEntry("emag", 1L).containsEntry("amparcat", 1L);
    }

    @Test
    void theWholeExportImportsOnceAndAgainIsANoOp() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction", Long.class)).isEqualTo(4026);
        long account = jdbc.queryForObject("SELECT id FROM account", Long.class);
        var again = uploads.importOne("copy.csv", Files.readAllBytes(FILE), Optional.of(account), Optional.empty());
        assertThat(again.outcome().newTransactions()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction", Long.class)).isEqualTo(4026);
    }
}
