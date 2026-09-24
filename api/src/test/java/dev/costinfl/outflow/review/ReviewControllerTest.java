package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.RecurringFixtures;
import dev.costinfl.outflow.recurring.Subscription;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP4.3: the review inbox over HTTP: cards, their order, every answer, skipping. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ReviewControllerTest.FixedClock.class})
class ReviewControllerTest {

    static final LocalDate TODAY = LocalDate.of(2026, 7, 20);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load(RecurringFixtures.LEDGER);
    }

    void load(String csv) throws Exception {
        uploads.importOne("ledger-" + csv.hashCode() + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    Inbox inbox() {
        var response = http.getForEntity("/api/review", Inbox.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    ReviewCard card(Inbox inbox, Kind kind, String name) {
        return inbox.cards().stream().filter(c -> c.kind() == kind && c.name().equals(name)).findFirst().orElseThrow();
    }

    long merchantId(String key) {
        return jdbc.queryForObject("SELECT id FROM merchant WHERE key = ?", Long.class, key);
    }

    String displayName(String key) {
        return jdbc.queryForObject("SELECT display_name FROM merchant WHERE key = ?", String.class, key);
    }

    @Test
    void oneCardPerQuestionLargestMoneyFirst() {
        var inbox = inbox();

        assertThat(inbox.cards()).isSortedAccordingTo(Comparator.comparingLong(ReviewCard::affectedMinor).reversed());
        assertThat(inbox.count()).isEqualTo(inbox.cards().size());
        assertThat(inbox.possible()).isEmpty();

        var subscriptions = inbox.cards().stream().filter(c -> c.kind() == Kind.SUBSCRIPTION).toList();
        assertThat(subscriptions).extracting(ReviewCard::merchantId).containsExactlyInAnyOrder(merchantId("NETFLIX"),
                merchantId("ENEL"), merchantId("ORANGE"), merchantId("WORLD CLASS"), merchantId("EMAG"));
        var netflix = card(inbox, Kind.SUBSCRIPTION, displayName("NETFLIX"));
        assertThat(netflix.affectedMinor()).isEqualTo(7 * 4999);
        assertThat(netflix.occurrences()).isEqualTo(7);
        assertThat(netflix.expectedAmountMinor()).isEqualTo(4999);
        assertThat(netflix.since()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(netflix.key()).isEqualTo("subscription:" + netflix.subscriptionId());

        // Uncategorized cards: one per merchant, the money of all its uncategorized transactions.
        Map<Long, Long> uncategorized = new java.util.HashMap<>();
        jdbc.query("SELECT merchant_id, sum(abs(amount_minor)) FROM transaction WHERE category_id IS NULL GROUP BY merchant_id",
                rs -> {
                    uncategorized.put(rs.getLong(1), rs.getLong(2));
                });
        assertThat(uncategorized).isNotEmpty();
        var merchantCards = inbox.cards().stream().filter(c -> c.kind() == Kind.UNCATEGORIZED_MERCHANT).toList();
        assertThat(merchantCards).hasSize(uncategorized.size());
        assertThat(merchantCards).allSatisfy(c -> assertThat(c.affectedMinor()).isEqualTo(uncategorized.get(c.merchantId())));
    }

    @Test
    void confirmingAnswersTheCard() {
        var netflix = card(inbox(), Kind.SUBSCRIPTION, displayName("NETFLIX"));

        var response = http.postForEntity("/api/subscriptions/" + netflix.subscriptionId() + "/confirm",
                Map.of("name", "Netflix Premium", "expectedAmountMinor", 5500), Subscription.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().state()).isEqualTo(Subscription.State.CONFIRMED);
        assertThat(response.getBody().name()).isEqualTo("Netflix Premium");
        assertThat(response.getBody().expectedAmountMinor()).isEqualTo(5500);
        assertThat(inbox().cards()).noneMatch(c -> netflix.key().equals(c.key()));
    }

    @Test
    void rejectAndEndFollowTheLifecycle() {
        long id = card(inbox(), Kind.SUBSCRIPTION, displayName("NETFLIX")).subscriptionId();

        var rejected = http.postForEntity("/api/subscriptions/" + id + "/reject", null, Subscription.class);
        assertThat(rejected.getBody().state()).isEqualTo(Subscription.State.REJECTED);

        assertThat(http.postForEntity("/api/subscriptions/" + id + "/end", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/subscriptions/999999/reject", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        long enel = card(inbox(), Kind.SUBSCRIPTION, displayName("ENEL")).subscriptionId();
        assertThat(http.postForEntity("/api/subscriptions/" + enel + "/confirm", Map.of("expectedAmountMinor", -1),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        http.postForEntity("/api/subscriptions/" + enel + "/confirm", null, Subscription.class);
        var ended = http.postForEntity("/api/subscriptions/" + enel + "/end", null, Subscription.class);
        assertThat(ended.getBody().state()).isEqualTo(Subscription.State.ENDED);
    }

    @Test
    void pickingACategoryAppliesToTheWholeMerchantAndRefreshesSubscriptions() {
        var gym = card(inbox(), Kind.UNCATEGORIZED_MERCHANT, displayName("WORLD CLASS"));
        assertThat(gym.transactionCount()).isEqualTo(6);

        // Say the "gym" is really a transfer to a friend: no longer spending, so no longer a subscription candidate.
        var response = http.postForEntity("/api/review/merchants/" + gym.merchantId() + "/category",
                Map.of("categoryId", 16), ReviewController.MerchantCategoryResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().changedTransactions()).isEqualTo(6);
        var after = inbox();
        assertThat(after.cards()).noneMatch(c -> c.merchantId() == gym.merchantId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription WHERE merchant_id = ?", Long.class,
                gym.merchantId())).isZero();
    }

    @Test
    void recategorizingATransactionAlsoRefreshesSubscriptions() {
        long enel = merchantId("ENEL");
        long txn = jdbc.queryForObject("SELECT min(id) FROM transaction WHERE merchant_id = ?", Long.class, enel);

        http.put("/api/transactions/" + txn + "/category", Map.of("categoryId", 16, "applyToMerchant", true));

        assertThat(inbox().cards()).noneMatch(c -> c.kind() == Kind.SUBSCRIPTION && c.merchantId() == enel);
    }

    @Test
    void badCategoryAnswers() {
        var gym = card(inbox(), Kind.UNCATEGORIZED_MERCHANT, displayName("WORLD CLASS"));

        assertThat(http.postForEntity("/api/review/merchants/" + gym.merchantId() + "/category",
                Map.of("categoryId", 9999), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/review/merchants/999999/category", Map.of("categoryId", 1), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void skippedCardsReturnAfterTheNextUpload() throws Exception {
        var first = inbox().cards().getFirst();

        assertThat(http.postForEntity("/api/review/skip", Map.of("key", first.key()), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(inbox().cards()).noneMatch(c -> c.key().equals(first.key()));
        assertThat(http.postForEntity("/api/review/skip", Map.of("key", ""), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        load("""
                Date,Description,Amount,Currency
                2026-07-18,CUMPARARE POS LIDL,-80.00,RON
                """);

        assertThat(inbox().cards()).anyMatch(c -> c.key().equals(first.key()));
    }
}
