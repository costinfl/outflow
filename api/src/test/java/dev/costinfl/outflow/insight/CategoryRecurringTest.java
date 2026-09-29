package dev.costinfl.outflow.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Cadence;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.SubscriptionService;
import dev.costinfl.outflow.txn.TransactionController.TransactionList;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.13: in a category's month, recurring payments are listed first, separately from variable spending (DESIGN:
 * Detail screens). Netflix (49.99 on the 15th, Jan–Mar 2026) and Spotify (29.99 on the 6th) are both detected; only
 * Netflix is confirmed. A one-off 100.00 at Zz Games is put in the same category by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CategoryRecurringTest {

    static final String LEDGER = """
            Date,Description,Amount,Currency
            2026-01-15,NETFLIX.COM,-49.99,RON
            2026-02-15,NETFLIX.COM,-49.99,RON
            2026-03-15,NETFLIX.COM,-49.99,RON
            2026-01-06,SPOTIFY,-29.99,RON
            2026-02-06,SPOTIFY,-29.99,RON
            2026-03-06,SPOTIFY,-29.99,RON
            2026-03-20,CUMPARARE POS ZZ GAMES,-100.00,RON
            """;

    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired CategoryService categories;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long category;
    long netflix;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "Main");
        uploads.importOne("l.csv", LEDGER.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8), Optional.of(account),
                Optional.empty());
        netflix = subscription("NETFLIX");
        subscriptions.confirm(netflix, Edits.NONE);
        // Everything in one category: Netflix's, whatever the keyword gave it.
        category = jdbc.queryForObject("""
                SELECT t.category_id FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                WHERE m.key = 'NETFLIX' LIMIT 1""", Long.class);
        for (long id : jdbc.queryForList("""
                SELECT t.id FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                WHERE m.key IN ('SPOTIFY', 'ZZ GAMES') AND t.category_id IS DISTINCT FROM ?""", Long.class, category)) {
            categories.setCategory(id, category, false);
        }
    }

    long subscription(String merchantKey) {
        return jdbc.queryForObject("""
                SELECT s.id FROM subscription s JOIN merchant m ON m.id = s.merchant_id WHERE m.key = ?""",
                Long.class, merchantKey);
    }

    CategoryDetail march() {
        return http.getForObject("/api/insights/categories/" + category + "?month=2026-03", CategoryDetail.class);
    }

    long drill(String query) {
        return http.getForObject("/api/transactions?month=2026-03&scope=SPEND&category=" + category + query,
                TransactionList.class).totalMinor();
    }

    @Test
    void confirmedRecurringPaymentsComeFirstAndTheRestIsVariable() {
        var d = march();

        assertThat(d.amountMinor()).isEqualTo(4_999 + 2_999 + 10_000);
        assertThat(d.recurring()).singleElement().satisfies(r -> {
            assertThat(r.subscriptionId()).isEqualTo(netflix);
            assertThat(r.name()).isEqualTo("Netflix");
            assertThat(r.cadence()).isEqualTo(Cadence.MONTHLY);
            assertThat(r.state()).isEqualTo("CONFIRMED");
            assertThat(r.amountMinor()).isEqualTo(4_999);
            assertThat(r.transactionCount()).isEqualTo(1);
        });
        assertThat(d.recurringMinor()).isEqualTo(4_999);
        // Spotify is only proposed: still a guess, so it is variable spending with Zz Games.
        assertThat(d.merchants()).extracting(CategoryDetail.MerchantAmount::amountMinor).containsExactly(10_000L, 2_999L);
        long variable = d.merchants().stream().mapToLong(CategoryDetail.MerchantAmount::amountMinor).sum();
        assertThat(d.recurringMinor() + variable).isEqualTo(d.amountMinor());
    }

    @Test
    void everyFigureDrillsToExactlyItsTransactions() {
        var d = march();

        assertThat(drill("")).isEqualTo(d.amountMinor());
        assertThat(drill("&recurring=true")).isEqualTo(d.recurringMinor());
        assertThat(drill("&recurring=false")).isEqualTo(d.amountMinor() - d.recurringMinor());
        assertThat(drill("&subscription=" + netflix)).isEqualTo(d.recurring().getFirst().amountMinor());
    }

    @Test
    void anEndedPaymentsChargesStayRecurringAndARejectedOnesAreVariable() {
        subscriptions.end(netflix);
        assertThat(march().recurring()).singleElement().satisfies(r -> assertThat(r.state()).isEqualTo("ENDED"));

        subscriptions.reject(subscription("SPOTIFY"));
        assertThat(march().recurring()).hasSize(1);
        assertThat(march().merchants()).hasSize(2);
    }
}
