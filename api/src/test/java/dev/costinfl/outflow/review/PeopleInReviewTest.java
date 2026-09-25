package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.MonthSummary;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.4: people in the review inbox. A person can be both paid and paying back, so their card splits money sent from
 * money received, and each direction is answered on its own: rent sent to a landlord is not what they pay back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PeopleInReviewTest {

    /** Rent to Ana twice (2 × 1,500), Ana paying back three times (350 in all), and one grocery run in June. */
    static final String LEDGER = """
            Date,Description,Amount,Currency
            2026-05-02,TRANSFER ANA POPESCU,-1500.00,RON
            2026-05-10,INCASARE ANA POPESCU,200.00,RON
            2026-06-02,TRANSFER ANA POPESCU,-1500.00,RON
            2026-06-03,CUMPARARE POS KAUFLAND,-100.00,RON
            2026-06-15,INCASARE ANA POPESCU,100.00,RON
            2026-06-20,INCASARE ANA POPESCU,50.00,RON
            """;

    @Autowired UploadService uploads;
    @Autowired CategoryService categories;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long account;
    long ana;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load();
        ana = jdbc.queryForObject("SELECT DISTINCT merchant_id FROM transaction WHERE description_raw LIKE '%ANA POPESCU'", Long.class);
    }

    void load() throws Exception {
        uploads.importOne("ana.csv", LEDGER.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), Optional.of(account),
                Optional.empty());
    }

    Optional<ReviewCard> anaCard() {
        Inbox inbox = http.getForObject("/api/review", Inbox.class);
        return inbox.cards().stream().filter(c -> c.kind() == Kind.UNCATEGORIZED_MERCHANT && c.merchantId() == ana).findFirst();
    }

    ResponseEntity<ReviewController.MerchantCategoryResult> answer(String code, String direction) {
        var body = new HashMap<String, Object>(Map.of("categoryId", categories.categoryId(code)));
        if (direction != null) {
            body.put("direction", direction);
        }
        return http.postForEntity("/api/review/merchants/" + ana + "/category", body, ReviewController.MerchantCategoryResult.class);
    }

    MonthSummary june() {
        return http.getForObject("/api/insights/month?month=2026-06", MonthSummary.class);
    }

    List<String> rules() {
        return jdbc.queryForList("""
                SELECT c.code || '/' || coalesce(r.direction, 'BOTH') FROM category_rule r JOIN category c ON c.id = r.category_id
                WHERE r.source = 'USER' ORDER BY 1""", String.class);
    }

    @Test
    void theCardSplitsMoneySentFromMoneyReceived() {
        var card = anaCard().orElseThrow();

        assertThat(card.transactionCount()).isEqualTo(5);
        assertThat(card.sentCount()).isEqualTo(2);
        assertThat(card.sentMinor()).isEqualTo(300_000);
        assertThat(card.receivedCount()).isEqualTo(3);
        assertThat(card.receivedMinor()).isEqualTo(35_000);
        assertThat(card.affectedMinor()).isEqualTo(335_000);
        assertThat(card.firstDate()).isEqualTo(LocalDate.of(2026, 5, 2));
    }

    @Test
    void answeringMoneySentLeavesTheCardForMoneyReceived() {
        var r = answer("HOUSING", "OUT");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().changedTransactions()).isEqualTo(2);
        var card = anaCard().orElseThrow();
        assertThat(card.sentCount()).isZero();
        assertThat(card.receivedCount()).isEqualTo(3);
        assertThat(card.affectedMinor()).isEqualTo(35_000);
        // June: rent and groceries; Ana's 150 back is not a refund of rent until the user says so
        assertThat(june().spentMinor()).isEqualTo(160_000);

        answer("TRANSFER", "IN");

        assertThat(anaCard()).isEmpty();
        assertThat(rules()).containsExactly("HOUSING/OUT", "TRANSFER/IN");
        assertThat(june().spentMinor()).isEqualTo(160_000);
        assertThat(june().incomeMinor()).isZero();
    }

    @Test
    void paidBackMoneyInTheSameCategoryNetsItsSpending() {
        answer("HOUSING", "OUT");
        answer("HOUSING", "IN");

        var housing = june().categories().stream().filter(c -> "HOUSING".equals(c.code())).findFirst().orElseThrow();
        assertThat(housing.spentMinor()).isEqualTo(135_000);
        assertThat(june().spentMinor()).isEqualTo(145_000);
    }

    @Test
    void receivedMoneyCanBeIncome() {
        answer("HOUSING", "OUT");
        answer("INCOME", "IN");

        assertThat(june().incomeMinor()).isEqualTo(15_000);
        assertThat(june().spentMinor()).isEqualTo(160_000);
    }

    @Test
    void aRuleForOneDirectionSplitsAnEarlierRuleForBoth() {
        answer("HOUSING", null);
        assertThat(rules()).containsExactly("HOUSING/BOTH");

        answer("TRANSFER", "IN");

        assertThat(rules()).containsExactly("HOUSING/OUT", "TRANSFER/IN");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT c.code FROM transaction t JOIN category c ON c.id = t.category_id
                WHERE t.merchant_id = ? AND t.amount_minor < 0""", String.class, ana)).containsExactly("HOUSING");

        answer("OTHER", null); // both again: one rule
        assertThat(rules()).containsExactly("OTHER/BOTH");
    }

    @Test
    void aManualCategoryOnOneTransactionSurvivesADirectionRule() {
        long first = jdbc.queryForObject(
                "SELECT min(id) FROM transaction WHERE merchant_id = ? AND amount_minor > 0", Long.class, ana);
        http.put("/api/transactions/" + first + "/category", Map.of("categoryId", categories.categoryId("ENTERTAINMENT"),
                "applyToMerchant", false));

        answer("TRANSFER", "IN");

        assertThat(jdbc.queryForMap("SELECT c.code, t.category_source FROM transaction t JOIN category c ON c.id = t.category_id WHERE t.id = ?", first))
                .containsEntry("code", "ENTERTAINMENT").containsEntry("category_source", "USER");
    }

    @Test
    void reImportingChangesNothing() throws Exception {
        answer("HOUSING", "OUT");
        answer("TRANSFER", "IN");
        var before = june();

        load();

        assertThat(categories.categorizeAll()).isZero();
        assertThat(june()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction", Long.class)).isEqualTo(6);
    }

    @Test
    void anUnknownDirectionIsRejected() {
        var body = Map.of("categoryId", categories.categoryId("HOUSING"), "direction", "SIDEWAYS");

        assertThat(http.postForEntity("/api/review/merchants/" + ana + "/category", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rules()).isEmpty();
    }
}
