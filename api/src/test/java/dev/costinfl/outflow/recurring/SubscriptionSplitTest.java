package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.review.ReviewCard;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
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
 * CP6.16: split (DESIGN: Subscription candidate lifecycle, "two plans from one merchant"). ZZ APPS bills a 9.99 plan on
 * the 5th of every month, and the user also buys things there for 11–12 now and then: within 25%, so the detector sees
 * one muddled "about 9.99, variable" stream. Today is 1 July 2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SubscriptionSplitTest.Today.class})
class SubscriptionSplitTest {

    @TestConfiguration
    static class Today {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main;
    long muddled;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        load("2026-01-05,ZZ APPS,-9.99", "2026-02-05,ZZ APPS,-9.99", "2026-03-05,ZZ APPS,-9.99", "2026-04-05,ZZ APPS,-9.99",
                "2026-05-05,ZZ APPS,-9.99", "2026-06-05,ZZ APPS,-9.99", "2026-02-20,ZZ APPS,-11.50", "2026-04-18,ZZ APPS,-11.00",
                "2026-06-11,ZZ APPS,-12.00");
        muddled = jdbc.queryForObject("SELECT id FROM subscription", Long.class);
    }

    void load(String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(",RON\r\n");
        }
        uploads.importOne("f" + csv.hashCode() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8),
                Optional.of(main), Optional.empty());
    }

    ResponseEntity<Subscription[]> split(long id, Long atMinor) {
        var body = new HashMap<String, Object>();
        body.put("atMinor", atMinor);
        return http.postForEntity("/api/subscriptions/" + id + "/split", body, Subscription[].class);
    }

    HttpStatus splitStatus(long id, Long atMinor) {
        var body = new HashMap<String, Object>();
        body.put("atMinor", atMinor);
        return HttpStatus.valueOf(http.postForEntity("/api/subscriptions/" + id + "/split", body, String.class)
                .getStatusCode().value());
    }

    @Test
    void theCardOffersTheRangeAndTheSplitLeavesTheCleanPlan() throws Exception {
        ReviewCard card = http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == ReviewCard.Kind.SUBSCRIPTION).findFirst().orElseThrow();
        assertThat(card.amountKind()).isEqualTo(AmountKind.VARIABLE);
        assertThat(card.lowestMinor()).isEqualTo(999);
        assertThat(card.highestMinor()).isEqualTo(1_200);

        var response = split(muddled, 1_050L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).singleElement().satisfies(s -> {
            assertThat(s.cadence()).isEqualTo(Cadence.MONTHLY);
            assertThat(s.amountKind()).isEqualTo(AmountKind.FIXED);
            assertThat(s.expectedAmountMinor()).isEqualTo(999);
            assertThat(s.anchorDay()).isEqualTo(5);
        });
        long plan = response.getBody()[0].id();
        assertThat(subscriptions.find(muddled)).isEmpty();
        assertThat(jdbc.queryForList("SELECT abs(amount_minor) FROM transaction WHERE subscription_id = ?", Long.class, plan))
                .hasSize(6).containsOnly(999L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE subscription_id IS NULL", Long.class))
                .isEqualTo(3); // the purchases: no stream of their own

        // The cut stays: after confirming, a July purchase is not the plan's, the July charge is.
        subscriptions.confirm(plan, Edits.NONE);
        load("2026-07-03,ZZ APPS,-11.20", "2026-07-05,ZZ APPS,-9.99");
        assertThat(jdbc.queryForList("SELECT abs(amount_minor) FROM transaction WHERE subscription_id = ?", Long.class, plan))
                .hasSize(7).containsOnly(999L);
        assertThat(subscriptions.find(plan).orElseThrow().nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription WHERE state = 'PROPOSED'", Long.class)).isZero();
    }

    @Test
    void onlyAProposalCanBeSplitAndOnlyBetweenItsCharges() {
        assertThat(splitStatus(muddled, 999L)).isEqualTo(HttpStatus.BAD_REQUEST); // nothing below
        assertThat(splitStatus(muddled, 1_201L)).isEqualTo(HttpStatus.BAD_REQUEST); // nothing at or above
        assertThat(splitStatus(muddled, null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(splitStatus(muddled, -5L)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(splitStatus(999_999, 1_050L)).isEqualTo(HttpStatus.NOT_FOUND);
        subscriptions.confirm(muddled, Edits.NONE);
        assertThat(splitStatus(muddled, 1_050L)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_split", Long.class)).isZero();
    }
}
