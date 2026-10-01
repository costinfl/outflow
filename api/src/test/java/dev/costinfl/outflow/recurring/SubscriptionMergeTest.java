package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.Subscription.State;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.15: merge (DESIGN: Subscription candidate lifecycle, "same service, two merchant keys"). A streaming service
 * billed as ZZSTREAM until February 2026 renamed itself ZZ STREAM PLUS from March: two merchants, two proposals, one
 * service. Today is 20 May 2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SubscriptionMergeTest.Today.class})
class SubscriptionMergeTest {

    @TestConfiguration
    static class Today {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-05-20T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        load(main, "2025-12-05,ZZSTREAM,-30.00", "2026-01-05,ZZSTREAM,-30.00", "2026-02-05,ZZSTREAM,-30.00",
                "2026-03-05,ZZ STREAM PLUS,-30.00", "2026-04-05,ZZ STREAM PLUS,-30.00", "2026-05-05,ZZ STREAM PLUS,-30.00");
    }

    void load(long account, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(",RON\r\n");
        }
        uploads.importOne("f" + account + "-" + csv.hashCode() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    /** The subscription whose charges include the one on {@code date}. */
    long subscriptionOn(String date) {
        return jdbc.queryForObject("""
                SELECT subscription_id FROM transaction WHERE account_id = ? AND booking_date = ?::date""",
                Long.class, main, date);
    }

    HttpStatus merge(long id, Long into) {
        var body = new HashMap<String, Object>();
        body.put("into", into);
        return HttpStatus.valueOf(http.postForEntity("/api/subscriptions/" + id + "/merge", body, String.class)
                .getStatusCode().value());
    }

    long charges(long subscriptionId) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE subscription_id = ?", Long.class, subscriptionId);
    }

    @Test
    void aProposalUnderTheNewNameJoinsTheConfirmedPayment() {
        long old = subscriptionOn("2025-12-05");
        long renamed = subscriptionOn("2026-03-05");
        assertThat(old).isNotEqualTo(renamed);
        subscriptions.confirm(old, new Edits("Streaming", null, null));

        assertThat(merge(renamed, old)).isEqualTo(HttpStatus.OK);

        var s = subscriptions.find(old).orElseThrow();
        assertThat(s.state()).isEqualTo(State.CONFIRMED);
        assertThat(s.name()).isEqualTo("Streaming"); // the user's
        assertThat(charges(old)).isEqualTo(6);
        assertThat(s.lastSeen()).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(s.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(subscriptions.find(renamed)).isEmpty();
        // One merchant from now on: later statements with the new name land on it too.
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT merchant_id) FROM transaction", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT match_type, pattern, merchant_key, source FROM merchant_alias WHERE source = 'USER'"))
                .containsEntry("match_type", "EXACT").containsEntry("source", "USER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription WHERE state = 'PROPOSED'", Long.class)).isZero();
    }

    @Test
    void twoProposalsBecomeOne() {
        long old = subscriptionOn("2025-12-05");
        long renamed = subscriptionOn("2026-03-05");

        assertThat(merge(renamed, old)).isEqualTo(HttpStatus.OK);

        var s = subscriptions.find(old).orElseThrow();
        assertThat(s.state()).isEqualTo(State.PROPOSED);
        assertThat(charges(old)).isEqualTo(6);
        assertThat(s.firstSeen()).isEqualTo(LocalDate.of(2025, 12, 5));
        assertThat(s.lastSeen()).isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription", Long.class)).isEqualTo(1);
    }

    @Test
    void onlyAProposalOfTheSameAccountCurrencyAndDirectionFromAnotherMerchantMerges() throws Exception {
        long old = subscriptionOn("2025-12-05");
        long renamed = subscriptionOn("2026-03-05");
        long card = ImportFixtures.newAccount(jdbc, "Card");
        load(card, "2026-01-12,ZZ MUSIC,-20.00", "2026-02-12,ZZ MUSIC,-20.00", "2026-03-12,ZZ MUSIC,-20.00");
        long music = jdbc.queryForObject("""
                SELECT s.id FROM subscription s JOIN merchant m ON m.id = s.merchant_id WHERE m.key LIKE '%MUSIC%'""", Long.class);

        assertThat(merge(renamed, renamed)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(merge(music, old)).isEqualTo(HttpStatus.BAD_REQUEST); // another account
        assertThat(merge(renamed, null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(merge(renamed, 999_999L)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(merge(999_999, old)).isEqualTo(HttpStatus.NOT_FOUND);
        subscriptions.confirm(old, Edits.NONE);
        assertThat(merge(old, renamed)).isEqualTo(HttpStatus.CONFLICT); // only a proposal is merged away
        subscriptions.reject(renamed);
        assertThat(merge(music, renamed)).isEqualTo(HttpStatus.CONFLICT); // into a rejected one
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_alias WHERE source = 'USER'", Long.class)).isZero();
    }
}
