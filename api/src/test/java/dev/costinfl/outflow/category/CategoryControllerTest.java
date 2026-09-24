package dev.costinfl.outflow.category;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryController.CategoryChange;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP2.3: recategorize one transaction, "apply to merchant", learning, undo, over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CategoryControllerTest {

    @Autowired TestRestTemplate http;
    @Autowired UploadService uploads;
    @Autowired CategoryService categories;
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        upload("generic-2026-01-to-03.csv");
    }

    void upload(String file) throws Exception {
        uploads.importOne(file, ImportFixtures.sample(file), Optional.of(account), Optional.empty());
    }

    List<Long> idsOf(String merchantKey) {
        return jdbc.queryForList("""
                SELECT t.id FROM transaction t JOIN merchant m ON m.id = t.merchant_id WHERE m.key = ? ORDER BY t.id""",
                Long.class, merchantKey);
    }

    Map<String, Long> sourcesOf(String merchantKey) {
        var out = new java.util.TreeMap<String, Long>();
        jdbc.query("""
                SELECT coalesce(c.code, '-') || '/' || coalesce(t.category_source, '-'), count(*)
                FROM transaction t JOIN merchant m ON m.id = t.merchant_id LEFT JOIN category c ON c.id = t.category_id
                WHERE m.key = ? GROUP BY 1""", rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        }, merchantKey);
        return out;
    }

    ResponseEntity<CategoryChange> put(long txId, String code, boolean apply) {
        return http.exchange("/api/transactions/" + txId + "/category", HttpMethod.PUT,
                new HttpEntity<>(Map.of("categoryId", categories.categoryId(code), "applyToMerchant", apply)),
                CategoryChange.class);
    }

    @Test
    void oneTransactionOnlyWithoutApply() {
        long tv = idsOf("KAUFLAND").getFirst();

        var r = put(tv, "SHOPPING", false);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().changedTransactions()).isEqualTo(1);
        assertThat(r.getBody().transaction().categoryCode()).isEqualTo("SHOPPING");
        assertThat(r.getBody().transaction().categorySource()).isEqualTo("USER");
        assertThat(r.getBody().transaction().merchantName()).isEqualTo("Kaufland");
        assertThat(sourcesOf("KAUFLAND")).isEqualTo(Map.of("SHOPPING/USER", 1L, "GROCERIES/KEYWORD", 11L));
    }

    /** M2 acceptance, over HTTP: a user rule is never overwritten by a re-run (re-upload triggers one). */
    @Test
    void applyToMerchantCreatesARuleThatSurvivesReUploads() throws Exception {
        List<Long> ids = idsOf("LIDL");
        put(ids.get(0), "OTHER", false); // an exception the user made earlier

        var r = put(ids.get(1), "HEALTH", true);

        assertThat(r.getBody().changedTransactions()).isEqualTo(11); // this one + 10 automatic, not the exception
        assertThat(sourcesOf("LIDL")).isEqualTo(Map.of("OTHER/USER", 1L, "HEALTH/RULE", 11L));
        assertThat(jdbc.queryForList("SELECT pattern FROM category_rule WHERE source = 'USER'", String.class)).containsExactly("LIDL");

        upload("generic-2026-02-to-04.csv"); // re-run of categorization + 4 new April rows
        categories.categorizeAll();

        assertThat(sourcesOf("LIDL")).isEqualTo(Map.of("OTHER/USER", 1L, "HEALTH/RULE", 15L));
    }

    @Test
    void applyingAgainReplacesTheRuleInsteadOfAddingOne() {
        long id = idsOf("BOLT").getFirst();
        put(id, "TRAVEL", true);

        put(id, "TRANSPORT", true);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_rule WHERE source = 'USER'", Long.class)).isEqualTo(1);
        assertThat(sourcesOf("BOLT")).isEqualTo(Map.of("TRANSPORT/RULE", 12L));
    }

    @Test
    void twoConsistentManualEditsTeachTheMerchantAndADisagreementUnteaches() {
        List<Long> ids = idsOf("STARBUCKS"); // 6 rows
        put(ids.get(0), "ENTERTAINMENT", false);
        assertThat(sourcesOf("STARBUCKS")).containsEntry("RESTAURANTS/KEYWORD", 5L);

        put(ids.get(1), "ENTERTAINMENT", false);
        assertThat(sourcesOf("STARBUCKS")).isEqualTo(Map.of("ENTERTAINMENT/USER", 2L, "ENTERTAINMENT/LEARNED", 4L));

        put(ids.get(2), "OTHER", false);
        assertThat(sourcesOf("STARBUCKS")).isEqualTo(
                Map.of("ENTERTAINMENT/USER", 2L, "OTHER/USER", 1L, "RESTAURANTS/KEYWORD", 3L));
    }

    @Test
    void undoReturnsTheTransactionToAutomatic() {
        long id = idsOf("NETFLIX").getFirst();
        put(id, "ENTERTAINMENT", false);

        var r = http.exchange("/api/transactions/" + id + "/category", HttpMethod.DELETE, null, CategoryChange.class);

        assertThat(r.getBody().transaction().categoryCode()).isEqualTo("SUBSCRIPTIONS");
        assertThat(r.getBody().transaction().categorySource()).isEqualTo("KEYWORD");
    }

    @Test
    void validation() {
        long id = idsOf("NETFLIX").getFirst();
        assertThat(http.exchange("/api/transactions/999999/category", HttpMethod.PUT,
                new HttpEntity<>(Map.of("categoryId", 1, "applyToMerchant", false)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/api/transactions/" + id + "/category", HttpMethod.PUT,
                new HttpEntity<>(Map.of("categoryId", 4242, "applyToMerchant", false)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void categoriesAreListedInOrder() {
        var list = http.getForObject("/api/categories", Category[].class);

        assertThat(list).hasSize(18);
        assertThat(list[0].code()).isEqualTo("GROCERIES");
        assertThat(list[14].kind()).isEqualTo(Category.Kind.INCOME);
    }
}
