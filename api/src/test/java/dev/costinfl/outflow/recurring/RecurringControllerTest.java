package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.InsightService;
import dev.costinfl.outflow.recurring.RecurringOverview.GroupKind;
import dev.costinfl.outflow.recurring.RecurringOverview.Item;
import dev.costinfl.outflow.recurring.RecurringOverview.Status;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.Subscription.State;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP4.4: the Recurring payments screen and the home "Committed every month" figure. Confirmed: Netflix 49.99 monthly
 * (Subscriptions), Enel 220.10 monthly variable (Bills), a 55.00 yearly domain (Subscriptions, 4.58 a month); Orange
 * confirmed then ended (Bills). World Class and eMAG stay suggestions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, RecurringControllerTest.FixedClock.class})
class RecurringControllerTest {

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
    @Autowired SubscriptionService subscriptions;
    @Autowired InsightService insights;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long account;
    long netflix, enel, domain, orange;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load(account, RecurringFixtures.LEDGER);
        load(account, """
                Date,Description,Amount,Currency
                2024-03-10,ROTLD DOMENIU,-55.00,RON
                2025-03-12,ROTLD DOMENIU,-55.00,RON
                2026-03-09,ROTLD DOMENIU,-55.00,RON
                """);
        netflix = subscriptions.confirm(proposedAt("NETFLIX.COM"), Edits.NONE).id();
        enel = subscriptions.confirm(proposedAt("ENEL ENERGIE"), Edits.NONE).id();
        domain = subscriptions.confirm(proposedAt("ROTLD DOMENIU"), Edits.NONE).id();
        orange = subscriptions.confirm(proposedAt("ORANGE ROMANIA"), Edits.NONE).id();
        subscriptions.end(orange);
    }

    void load(long accountId, String csv) throws Exception {
        uploads.importOne("ledger-" + csv.hashCode() + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(accountId), Optional.empty());
    }

    long proposedAt(String description) {
        return jdbc.queryForObject("""
                SELECT s.id FROM subscription s
                WHERE s.state = 'PROPOSED' AND s.merchant_id = (SELECT merchant_id FROM transaction WHERE description_raw = ? LIMIT 1)""",
                Long.class, description);
    }

    RecurringOverview get(String query) {
        ResponseEntity<RecurringOverview> r = http.getForEntity("/api/subscriptions" + query, RecurringOverview.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void committedTodayGroupedAndSortedByMonthlyCost() {
        var o = get("");

        assertThat(o.monthlyMinor()).isEqualTo(4999 + 22010 + 458);
        assertThat(o.yearlyMinor()).isEqualTo(59_988 + 264_120 + 5500);
        assertThat(o.countedCount()).isEqualTo(3);
        assertThat(o.suggestionCount()).isEqualTo(3); // World Class, eMAG, and the salary as recurring income (CP6.5)
        assertThat(o.incomeMonthlyMinor()).isZero(); // proposed, not confirmed
        assertThat(o.groups()).extracting(RecurringOverview.Group::kind).containsExactly(GroupKind.SUBSCRIPTIONS, GroupKind.BILLS);

        var subs = o.groups().get(0);
        assertThat(subs.items()).extracting(Item::id).containsExactly(netflix, domain);
        assertThat(subs.monthlyMinor()).isEqualTo(4999 + 458);
        var domainItem = subs.items().get(1);
        assertThat(domainItem.cadence()).isEqualTo(Cadence.YEARLY);
        assertThat(domainItem.monthlyMinor()).isEqualTo(458);
        assertThat(domainItem.yearlyMinor()).isEqualTo(5500);
        assertThat(domainItem.nextExpectedDate()).isEqualTo(LocalDate.of(2027, 3, 10));

        var bills = o.groups().get(1);
        assertThat(bills.items()).extracting(Item::id).containsExactly(enel, orange);
        assertThat(bills.monthlyMinor()).isEqualTo(22010);
        assertThat(bills.items().get(0).categoryName()).isEqualTo("Utilities");
        var ended = bills.items().get(1);
        assertThat(ended.status()).isEqualTo(Status.ENDED);
        assertThat(ended.counted()).isFalse();
        assertThat(ended.nextExpectedDate()).isNull();
    }

    @Test
    void aPastMonthCountsWhatWasActiveThenAndEqualsTheHomeFigure() {
        var feb = get("?month=2026-02");

        // Orange ended after February, so it still counts there.
        assertThat(feb.monthlyMinor()).isEqualTo(4999 + 22010 + 458 + 6500);
        assertThat(feb.countedCount()).isEqualTo(4);

        var home = insights.month(YearMonth.of(2026, 2), "RON");
        assertThat(home.committed().monthlyMinor()).isEqualTo(feb.monthlyMinor());
        assertThat(home.committed().count()).isEqualTo(feb.countedCount());
        assertThat(home.committed().sharePct())
                .isEqualTo((int) Math.round(feb.monthlyMinor() * 100.0 / home.spentMinor()));

        // December 2025: only what had started by then (Orange from 31 Dec, the domain from 2024).
        var dec = get("?month=2025-12");
        assertThat(dec.groups().stream().flatMap(g -> g.items().stream())).extracting(Item::id)
                .containsExactlyInAnyOrder(orange, domain);
        assertThat(dec.monthlyMinor()).isEqualTo(6500 + 458);
        assertThat(insights.month(YearMonth.of(2025, 12), "RON").committed().monthlyMinor()).isEqualTo(dec.monthlyMinor());
    }

    @Test
    void coverageSaysWhatHistoryCanDetect() throws Exception {
        long card = ImportFixtures.newAccount(jdbc, "Card");
        load(card, """
                Date,Description,Amount,Currency
                2026-05-02,CUMPARARE POS LIDL,-10.00,RON
                2026-06-28,CUMPARARE POS LIDL,-12.00,RON
                """);

        var coverage = get("").coverage();

        assertThat(coverage).hasSize(2);
        var main = coverage.get(0);
        assertThat(main.accountName()).isEqualTo("Main");
        assertThat(main.from()).isEqualTo(LocalDate.of(2024, 3, 10));
        assertThat(main.to()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(main.months()).isEqualTo(29);
        assertThat(main.monthly()).isTrue();
        assertThat(main.yearly()).isTrue();
        var short_ = coverage.get(1);
        assertThat(short_.months()).isEqualTo(2);
        assertThat(short_.monthly()).isFalse();
        assertThat(short_.yearly()).isFalse();
    }

    @Test
    void renameAndErrors() {
        var renamed = patch(netflix, Map.of("name", "  Netflix 4K "), Subscription.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().name()).isEqualTo("Netflix 4K");
        assertThat(get("").groups().get(0).items().get(0).name()).isEqualTo("Netflix 4K");

        assertThat(patch(orange, Map.of("name", "Orange (old)"), Subscription.class).getBody().state()).isEqualTo(State.ENDED);
        long proposal = proposedAt("WORLD CLASS");
        assertThat(patch(proposal, Map.of("name", "Gym"), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(patch(999_999, Map.of("name", "X"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(netflix, Map.of("name", " "), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/subscriptions?month=2026-13", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    <T> ResponseEntity<T> patch(long id, Map<String, String> body, Class<T> type) {
        return http.exchange("/api/subscriptions/" + id, HttpMethod.PATCH, new HttpEntity<>(body), type);
    }
}
