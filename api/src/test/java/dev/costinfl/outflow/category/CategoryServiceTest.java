package dev.costinfl.outflow.category;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP2.2 / M2 acceptance: coverage on the samples, and user decisions are never overwritten by automation. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CategoryServiceTest {

    @Autowired UploadService uploads;
    @Autowired CategoryService categories;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "Main");
        for (String file : new String[] {"generic-2026-01-to-03.csv", "generic-2026-02-to-04.csv"}) {
            uploads.importOne(file, ImportFixtures.sample(file), Optional.of(account), Optional.empty());
        }
    }

    Map<String, String> categoryByMerchant() {
        var out = new TreeMap<String, String>();
        jdbc.query("""
                SELECT DISTINCT m.key, coalesce(c.code, '-') || '/' || coalesce(t.category_source, '-')
                FROM transaction t JOIN merchant m ON m.id = t.merchant_id LEFT JOIN category c ON c.id = t.category_id""",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2));
                });
        return out;
    }

    String categoryOf(String merchantKey) {
        return categoryByMerchant().get(merchantKey);
    }

    @Test
    void seededTreeHasTheDesignCategoriesWithKinds() {
        // DESIGN's 18 seeds plus Insurance (V12, decided in spec question 27)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Long.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("SELECT kind FROM category WHERE code = 'INSURANCE'", String.class)).isEqualTo("SPEND");
        assertThat(jdbc.queryForList("SELECT code FROM category WHERE kind <> 'SPEND' ORDER BY code", String.class))
                .containsExactly("INCOME", "TRANSFER");
    }

    @Test
    void uploadsAreCategorizedByKeywordsOnTheSamples() {
        assertThat(categoryByMerchant()).isEqualTo(Map.ofEntries(
                Map.entry("BOLT", "TRANSPORT/KEYWORD"), Map.entry("CONT ECONOMII", "TRANSFER/KEYWORD"),
                Map.entry("ENEL", "UTILITIES/KEYWORD"), Map.entry("KAUFLAND", "GROCERIES/KEYWORD"),
                Map.entry("LIDL", "GROCERIES/KEYWORD"), Map.entry("NETFLIX", "SUBSCRIPTIONS/KEYWORD"),
                Map.entry("ORANGE", "TELECOM/KEYWORD"), Map.entry("RENT PROPRIETAR", "HOUSING/KEYWORD"),
                Map.entry("SALARIU ACME SRL", "INCOME/KEYWORD"), Map.entry("SPOTIFY", "SUBSCRIPTIONS/KEYWORD"),
                Map.entry("STARBUCKS", "RESTAURANTS/KEYWORD")));
        assertThat(jdbc.queryForObject("SELECT DISTINCT category_confidence FROM transaction", java.math.BigDecimal.class))
                .isEqualByComparingTo("0.70");
    }

    /** M2 acceptance: ≥ 80% of spend (outgoing, excluding transfers) categorized. */
    @Test
    void atLeast80PercentOfSpendIsCategorized() {
        var share = jdbc.queryForObject("""
                SELECT sum(-t.amount_minor) FILTER (WHERE t.category_id IS NOT NULL)::numeric / sum(-t.amount_minor)
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.amount_minor < 0 AND c.kind IS DISTINCT FROM 'TRANSFER'""", java.math.BigDecimal.class);

        assertThat(share).isGreaterThanOrEqualTo(new java.math.BigDecimal("0.80"));
    }

    /** M2 acceptance: a user decision is never overwritten by a re-run, whatever the rules say. */
    @Test
    void aManualCategoryOnATransactionSurvivesEveryReRun() {
        long other = categories.categoryId("OTHER");
        long id = jdbc.queryForObject("SELECT min(t.id) FROM transaction t JOIN merchant m ON m.id = t.merchant_id WHERE m.key = 'KAUFLAND'", Long.class);
        jdbc.update("UPDATE transaction SET category_id = ?, category_source = 'USER', category_confidence = 1 WHERE id = ?", other, id);
        jdbc.update("INSERT INTO category_rule (household_id, source, priority, match_type, pattern, category_id) VALUES (1, 'USER', 10, 'MERCHANT', 'KAUFLAND', ?)",
                categories.categoryId("SHOPPING"));
        jdbc.update("UPDATE merchant SET default_category_id = ? WHERE key = 'KAUFLAND'", categories.categoryId("HEALTH"));

        categories.categorizeAll();
        categories.categorizeAll();

        assertThat(jdbc.queryForMap("SELECT category_id, category_source FROM transaction WHERE id = ?", id))
                .containsEntry("category_id", other).containsEntry("category_source", "USER");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                WHERE m.key = 'KAUFLAND' AND t.category_source = 'RULE'""", Long.class)).isEqualTo(15);
    }

    @Test
    void userRuleBeatsLearnedWhichBeatsKeyword() {
        jdbc.update("UPDATE merchant SET default_category_id = ? WHERE key = 'LIDL'", categories.categoryId("SHOPPING"));
        categories.categorizeAll();
        assertThat(categoryOf("LIDL")).isEqualTo("SHOPPING/LEARNED");

        jdbc.update("INSERT INTO category_rule (household_id, source, priority, match_type, pattern, category_id) VALUES (1, 'USER', 10, 'MERCHANT', 'LIDL', ?)",
                categories.categoryId("OTHER"));
        categories.categorizeAll();
        assertThat(categoryOf("LIDL")).isEqualTo("OTHER/RULE");

        // and it all unwinds: recomputable from rules, never stuck on an old answer
        jdbc.update("DELETE FROM category_rule WHERE source = 'USER'");
        jdbc.update("UPDATE merchant SET default_category_id = NULL");
        categories.categorizeAll();
        assertThat(categoryOf("LIDL")).isEqualTo("GROCERIES/KEYWORD");
    }

    @Test
    void aMerchantNoRuleKnowsStaysUncategorizedUntilARuleArrives() throws Exception {
        long account = jdbc.queryForObject("SELECT min(id) FROM account", Long.class);
        uploads.importOne("new.csv", "Date,Description,Amount,Currency\r\n2026-05-02,CUMPARARE POS ZZ WIDGETS BUCURESTI,-99.00,RON\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), Optional.of(account), Optional.empty());
        assertThat(categoryOf("ZZ WIDGETS")).isEqualTo("-/-");

        jdbc.update("INSERT INTO category_rule (household_id, source, priority, match_type, pattern, category_id) VALUES (1, 'USER', 10, 'MERCHANT', 'ZZ WIDGETS', ?)",
                categories.categoryId("SHOPPING"));
        assertThat(categories.categorizeAll()).isEqualTo(1);
        assertThat(categoryOf("ZZ WIDGETS")).isEqualTo("SHOPPING/RULE");
    }

    @Test
    void reRunWithoutChangesWritesNothing() {
        assertThat(categories.categorizeAll()).isZero();
    }
}
