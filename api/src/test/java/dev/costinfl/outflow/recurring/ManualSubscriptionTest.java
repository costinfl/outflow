package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Subscription.State;
import dev.costinfl.outflow.txn.TransactionController.TransactionList;
import dev.costinfl.outflow.txn.TransactionView;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.14: "Mark as recurring" (DESIGN: Subscription candidate lifecycle, Manual add). One transaction becomes a
 * confirmed recurring payment with a cadence; later charges link like any confirmed one's. Today is 1 March 2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ManualSubscriptionTest.Today.class})
class ManualSubscriptionTest {

    @TestConfiguration
    static class Today {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
    }

    void load(long account, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(",RON\r\n");
        }
        uploads.importOne("f" + account + "-" + csv.hashCode() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    long tx(String date) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE account_id = ? AND booking_date = ?::date LIMIT 1",
                Long.class, main, date);
    }

    ResponseEntity<Subscription> mark(Long transactionId, String cadence, String name) {
        var body = new HashMap<String, Object>();
        body.put("transactionId", transactionId);
        body.put("cadence", cadence);
        body.put("name", name);
        return http.postForEntity("/api/subscriptions", body, Subscription.class);
    }

    HttpStatus markStatus(Long transactionId, String cadence) {
        var body = new HashMap<String, Object>();
        body.put("transactionId", transactionId);
        body.put("cadence", cadence);
        return HttpStatus.valueOf(http.postForEntity("/api/subscriptions", body, String.class).getStatusCode().value());
    }

    @Test
    void aYearlyRenewalSeenOnceBecomesAConfirmedRecurringPayment() throws Exception {
        load(main, "2025-02-14,PLATA CARD DOMENIU RO,-55.00");

        var response = mark(tx("2025-02-14"), "YEARLY", "Domain renewal");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var s = response.getBody();
        assertThat(s.state()).isEqualTo(State.CONFIRMED);
        assertThat(s.name()).isEqualTo("Domain renewal");
        assertThat(s.cadence()).isEqualTo(Cadence.YEARLY);
        assertThat(s.expectedAmountMinor()).isEqualTo(5_500);
        assertThat(s.direction()).isEqualTo(Direction.OUT);
        assertThat(s.anchorDay()).isEqualTo(14);
        assertThat(s.anchorMonth()).isEqualTo(2);
        assertThat(s.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 2, 14));
        // Shown like any confirmed payment: on the Recurring screen and on the transaction itself.
        assertThat(http.getForObject("/api/subscriptions", RecurringOverview.class).groups().stream()
                .flatMap(g -> g.items().stream()).filter(i -> i.id() == s.id())).hasSize(1);
        TransactionView row = http.getForObject("/api/transactions?month=2025-02", TransactionList.class).items().getFirst();
        assertThat(row.subscriptionId()).isEqualTo(s.id());
        assertThat(row.subscriptionName()).isEqualTo("Domain renewal");
        assertThat(row.subscriptionState()).isEqualTo("CONFIRMED");

        // A year later the renewal arrives two days late: it links, and the next one is due in 2027.
        load(main, "2026-02-16,PLATA CARD DOMENIU RO,-55.00");
        var after = subscriptions.find(s.id()).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT subscription_id FROM transaction WHERE id = ?", Long.class, tx("2026-02-16")))
                .isEqualTo(s.id());
        assertThat(after.lastSeen()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(after.nextExpectedDate()).isEqualTo(LocalDate.of(2027, 2, 14));
    }

    @Test
    void aDifferentPriceNextTimeIsAPriceChangeQuestion() throws Exception {
        load(main, "2025-02-14,PLATA CARD DOMENIU RO,-55.00");
        long id = mark(tx("2025-02-14"), "YEARLY", null).getBody().id();

        load(main, "2026-02-14,PLATA CARD DOMENIU RO,-60.00");

        assertThat(jdbc.queryForMap("""
                SELECT previous_amount_minor, amount_minor FROM subscription_alert
                WHERE subscription_id = ? AND kind = 'PRICE_CHANGE' AND resolution IS NULL""", id))
                .containsEntry("previous_amount_minor", 5_500L).containsEntry("amount_minor", 6_000L);
        // Marked on 1 March, after the 14 February renewal was due: missed, until the charge arrived and answered it.
        assertThat(jdbc.queryForObject("SELECT resolution FROM subscription_alert WHERE subscription_id = ? AND kind = 'MISSED'",
                String.class, id)).isEqualTo("CHARGED");
    }

    @Test
    void chargesAlreadyImportedAfterItLinkAtOnceAndMoneyInIsRecurringIncome() throws Exception {
        load(main, "2026-01-20,PLATA GYM FIT,-120.00", "2026-02-20,PLATA GYM FIT,-120.00",
                "2026-01-05,INCASARE CHIRIE APARTAMENT,1500.00");

        var gym = mark(tx("2026-01-20"), "MONTHLY", null).getBody();
        assertThat(gym.name()).isEqualTo(jdbc.queryForObject(
                "SELECT m.display_name FROM transaction t JOIN merchant m ON m.id = t.merchant_id WHERE t.id = ?",
                String.class, tx("2026-01-20")));
        assertThat(gym.lastSeen()).isEqualTo(LocalDate.of(2026, 2, 20));
        assertThat(gym.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 3, 20));

        var rent = mark(tx("2026-01-05"), "MONTHLY", "Rent received").getBody();
        assertThat(rent.direction()).isEqualTo(Direction.IN);
        assertThat(rent.expectedAmountMinor()).isEqualTo(150_000);
    }

    @Test
    void onlyAFreeNonTransferTransactionWithACadenceCanBeMarked() throws Exception {
        load(main, "2025-12-15,NETFLIX.COM,-49.99", "2026-01-15,NETFLIX.COM,-49.99", "2026-02-15,NETFLIX.COM,-49.99");
        long savings = ImportFixtures.newAccount(jdbc, "Savings");
        load(main, "2026-02-02,ORDIN PLATA,-700.00");
        load(savings, "2026-02-03,INCASARE ORDIN PLATA,700.00");
        long netflix = tx("2026-02-15");
        assertThat(jdbc.queryForObject("SELECT subscription_id FROM transaction WHERE id = ?", Long.class, netflix))
                .as("a detected proposal already holds it").isNotNull();

        assertThat(markStatus(netflix, "MONTHLY")).isEqualTo(HttpStatus.CONFLICT);
        assertThat(markStatus(tx("2026-02-02"), "MONTHLY")).isEqualTo(HttpStatus.CONFLICT); // an own-account transfer
        assertThat(markStatus(999_999L, "MONTHLY")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(markStatus(netflix, null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(markStatus(null, "MONTHLY")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription WHERE state = 'CONFIRMED'", Long.class)).isZero();
    }
}
