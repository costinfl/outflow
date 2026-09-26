package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.MonthSummary;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.SubscriptionService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.10: accuracy as DESIGN defines it, the share of spending "categorized and reviewed", and the "Is this right?"
 * cards that raise it. March 2026: eight merchants only a keyword categorized (Kaufland 400, Lidl 300, Starbucks 100,
 * Bolt 80, Spotify 60, Netflix 50, Orange 40 and Enel 30 on the 20th) and one uncategorized (Zz Widgets 40): 1,100.
 * Netflix and Spotify were also charged in January and February, so they are subscription proposals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReviewedAccuracyTest {

    static final String LEDGER = """
            Date,Description,Amount,Currency
            2026-03-02,CUMPARARE POS KAUFLAND,-400.00,RON
            2026-03-03,CUMPARARE POS LIDL,-300.00,RON
            2026-03-04,CUMPARARE POS STARBUCKS,-100.00,RON
            2026-03-05,BOLT.EU/R/1,-80.00,RON
            2026-01-06,SPOTIFY,-60.00,RON
            2026-02-06,SPOTIFY,-60.00,RON
            2026-03-06,SPOTIFY,-60.00,RON
            2026-01-15,NETFLIX.COM,-50.00,RON
            2026-02-16,NETFLIX.COM,-50.00,RON
            2026-03-16,NETFLIX.COM,-50.00,RON
            2026-03-18,ORANGE ROMANIA,-40.00,RON
            2026-03-20,ENEL ENERGIE,-30.00,RON
            2026-03-11,CUMPARARE POS ZZ WIDGETS,-40.00,RON
            """;

    @Autowired UploadService uploads;
    @Autowired CategoryService categories;
    @Autowired SubscriptionService subscriptions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "Main");
        uploads.importOne("march.csv", LEDGER.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), Optional.of(account),
                Optional.empty());
    }

    MonthSummary march() {
        return http.getForObject("/api/insights/month?month=2026-03", MonthSummary.class);
    }

    List<ReviewCard> confirmCards() {
        return http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == Kind.CONFIRM_CATEGORY).toList();
    }

    long merchant(String key) {
        return jdbc.queryForObject("SELECT id FROM merchant WHERE key = ?", Long.class, key);
    }

    void answer(String key, String categoryCode) {
        var r = http.postForEntity("/api/review/merchants/" + merchant(key) + "/category",
                Map.of("categoryId", categories.categoryId(categoryCode), "direction", "OUT"), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void keywordGuessesAreCategorizedButNotReviewed() {
        var m = march();

        assertThat(m.spentMinor()).isEqualTo(110_000);
        assertThat(m.categorizedPct()).isEqualTo(96); // all but Zz Widgets' 40
        assertThat(m.reviewedPct()).isZero();
    }

    @Test
    void theLargestUnreviewedMerchantsAreAskedFiveAtATime() {
        var cards = confirmCards();

        assertThat(cards).extracting(ReviewCard::name).hasSize(5);
        // Money over all time, like every inbox card: Spotify's 180 and Netflix's 150 come before Starbucks' 100.
        assertThat(cards).extracting(ReviewCard::merchantId).containsExactly(merchant("KAUFLAND"), merchant("LIDL"),
                merchant("SPOTIFY"), merchant("NETFLIX"), merchant("STARBUCKS"));
        var kaufland = cards.getFirst();
        assertThat(kaufland.categoryName()).isEqualTo("Groceries");
        assertThat(kaufland.categoryId()).isEqualTo(categories.categoryId("GROCERIES"));
        assertThat(kaufland.affectedMinor()).isEqualTo(40_000);
        assertThat(kaufland.transactionCount()).isEqualTo(1);
        assertThat(kaufland.key()).isEqualTo("category:" + merchant("KAUFLAND") + ":RON");

        // Answering one brings up the next.
        answer("KAUFLAND", "GROCERIES");
        assertThat(confirmCards()).extracting(ReviewCard::merchantId).containsExactly(merchant("LIDL"),
                merchant("SPOTIFY"), merchant("NETFLIX"), merchant("STARBUCKS"), merchant("BOLT"));
    }

    @Test
    void eachKindOfAnswerMakesSpendingReviewed() {
        answer("KAUFLAND", "GROCERIES"); // "Right": the keyword's category, now the user's rule
        assertThat(march().reviewedPct()).isEqualTo(36); // 400 of 1,100

        answer("STARBUCKS", "ENTERTAINMENT"); // "Change": another category
        assertThat(march().reviewedPct()).isEqualTo(45); // + 100

        long netflix = jdbc.queryForObject("""
                SELECT s.id FROM subscription s JOIN merchant m ON m.id = s.merchant_id WHERE m.key = 'NETFLIX'""", Long.class);
        subscriptions.confirm(netflix, Edits.NONE); // a confirmed subscription's charges
        assertThat(march().reviewedPct()).isEqualTo(50); // + 50

        long widgets = jdbc.queryForObject("SELECT id FROM transaction WHERE merchant_id = ?", Long.class, merchant("ZZ WIDGETS"));
        categories.setCategory(widgets, categories.categoryId("SHOPPING"), false); // one transaction by hand
        assertThat(march().reviewedPct()).isEqualTo(54); // + 40

        assertThat(confirmCards()).extracting(ReviewCard::merchantId).doesNotContain(merchant("KAUFLAND"),
                merchant("STARBUCKS"), merchant("NETFLIX"));
    }

    @Test
    void theAccuracyFigureFollowsTheAccountsFilter() {
        long other = ImportFixtures.newAccount(jdbc, "Card");
        answer("KAUFLAND", "GROCERIES");

        assertThat(http.getForObject("/api/insights/month?month=2026-03&accounts=" + other, MonthSummary.class)
                .reviewedPct()).isZero();
        assertThat(march().reviewedPct()).isEqualTo(36);
    }
}
