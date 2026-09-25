package dev.costinfl.outflow.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.RecurringFixtures;
import dev.costinfl.outflow.recurring.RecurringOverview;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.SubscriptionService;
import dev.costinfl.outflow.txn.TransactionController.TransactionList;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/**
 * CP5.4: the home accounts filter. Main holds the seeded recurring ledger (January–July 2026); Card, by hand: February
 * Lidl 200.00; March Lidl 100.00 and Starbucks 30.00. Every filtered figure equals its filtered drill-through, and the
 * accounts add up to "all accounts".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, AccountsFilterTest.FixedClock.class})
class AccountsFilterTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(LocalDate.of(2026, 7, 20).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main, card;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        card = ImportFixtures.newAccount(jdbc, "Card");
        load(main, RecurringFixtures.LEDGER);
        load(card, """
                Date,Description,Amount,Currency
                2026-02-20,CUMPARARE POS LIDL,-200.00,RON
                2026-03-10,CUMPARARE POS LIDL,-100.00,RON
                2026-03-12,CUMPARARE POS STARBUCKS,-30.00,RON
                """);
    }

    void load(long account, String csv) throws Exception {
        uploads.importOne("f" + account + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    MonthSummary month(String query) {
        var r = http.getForEntity("/api/insights/month?month=2026-03" + query, MonthSummary.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    TransactionList list(String query) {
        return http.getForObject("/api/transactions?month=2026-03" + query, TransactionList.class);
    }

    @Test
    void oneAccountHandComputed() {
        var s = month("&accounts=" + card);

        assertThat(s.spentMinor()).isEqualTo(13_000);
        assertThat(s.incomeMinor()).isZero();
        assertThat(s.availableMonths()).containsExactly("2026-02", "2026-03");
        assertThat(s.baselineMonths()).isEqualTo(1); // only February has Card data
        assertThat(s.averageSpentMinor()).isEqualTo(20_000);
        assertThat(s.deltaPct()).isEqualTo(-35);
        assertThat(s.categories()).extracting(MonthSummary.CategorySpend::code, MonthSummary.CategorySpend::spentMinor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GROCERIES", 10_000L),
                        org.assertj.core.groups.Tuple.tuple("RESTAURANTS", 3_000L));
    }

    @Test
    void filteredFiguresEqualTheirFilteredDrillThrough() {
        for (String filter : new String[] {"", "&accounts=" + main, "&accounts=" + card, "&accounts=" + main + "," + card}) {
            var s = month(filter);
            assertThat(list("&scope=SPEND" + filter).totalMinor()).as(filter).isEqualTo(s.spentMinor());
            assertThat(list("&scope=INCOME" + filter).totalMinor()).as(filter).isEqualTo(s.incomeMinor());
            for (var c : s.categories()) {
                String category = c.categoryId() == null ? "&uncategorized=true" : "&category=" + c.categoryId();
                var drill = list("&scope=SPEND" + category + filter);
                assertThat(drill.totalMinor()).as(filter + category).isEqualTo(c.spentMinor());
                assertThat(drill.count()).as(filter + category).isEqualTo(c.transactionCount());
            }
        }
    }

    @Test
    void accountsAddUpToAllAccounts() {
        var all = month("");
        assertThat(month("&accounts=" + main).spentMinor() + month("&accounts=" + card).spentMinor())
                .isEqualTo(all.spentMinor());
        assertThat(month("&accounts=" + main + "," + card)).isEqualTo(all);
        assertThat(month("&accounts=999999").spentMinor()).isZero();
    }

    @Test
    void categoryDetailFollowsTheFilter() {
        long groceries = jdbc.queryForObject("SELECT id FROM category WHERE code = 'GROCERIES'", Long.class);

        var d = http.getForObject("/api/insights/categories/" + groceries + "?month=2026-03&accounts=" + card,
                CategoryDetail.class);

        assertThat(d.amountMinor()).isEqualTo(10_000);
        assertThat(d.merchants()).singleElement().satisfies(m -> assertThat(m.transactionCount()).isEqualTo(1));
    }

    @Test
    void committedFollowsTheFilterAndEqualsTheRecurringScreen() {
        long netflix = jdbc.queryForObject("""
                SELECT s.id FROM subscription s JOIN merchant m ON m.id = s.merchant_id WHERE m.key = 'NETFLIX'""", Long.class);
        subscriptions.confirm(netflix, Edits.NONE);

        assertThat(month("&accounts=" + main).committed().monthlyMinor()).isEqualTo(4999);
        assertThat(month("&accounts=" + card).committed().monthlyMinor()).isZero();
        var screen = http.getForObject("/api/subscriptions?month=2026-03&accounts=" + main, RecurringOverview.class);
        assertThat(screen.monthlyMinor()).isEqualTo(4999);
        var cardScreen = http.getForObject("/api/subscriptions?accounts=" + card, RecurringOverview.class);
        assertThat(cardScreen.groups()).isEmpty();
        assertThat(cardScreen.coverage()).singleElement().satisfies(c -> assertThat(c.accountName()).isEqualTo("Card"));
        assertThat(cardScreen.suggestionCount()).isZero();
    }

    @Test
    void badAccountIdsAreRejected() {
        assertThat(http.getForEntity("/api/insights/month?accounts=abc", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
