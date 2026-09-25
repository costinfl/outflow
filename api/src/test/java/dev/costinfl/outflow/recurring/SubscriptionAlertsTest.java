package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.RecurringOverview.Item;
import dev.costinfl.outflow.recurring.RecurringOverview.Status;
import dev.costinfl.outflow.recurring.Subscription.EndedBy;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.Subscription.State;
import dev.costinfl.outflow.review.ReviewCard;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
 * CP5.3: prediction and alerts on confirmed subscriptions (DESIGN: Recurrence detection, step 5). Netflix 49.99 monthly
 * on the 15th (last 15 Jul, next 15 Aug) and World Class 250.00 monthly on the 3rd (last 3 Jul, next 3 Aug; missed after
 * 3 + 3 + 3 days, i.e. from 10 Aug) are confirmed. Uploads run as of 20 Jul; later checks pass their own date.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SubscriptionAlertsTest.FixedClock.class})
class SubscriptionAlertsTest {

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
    @Autowired RecurringService recurring;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long account, netflix, gym;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load(RecurringFixtures.LEDGER);
        netflix = subscriptions.confirm(proposed("NETFLIX"), Edits.NONE).id();
        gym = subscriptions.confirm(proposed("WORLD CLASS"), Edits.NONE).id();
    }

    void load(String csv) throws Exception {
        uploads.importOne("f" + csv.hashCode() + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    void charges(String description, String amount, String... dates) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\n");
        for (String d : dates) {
            csv.append(d).append(',').append(description).append(',').append(amount).append(",RON\n");
        }
        load(csv.toString());
    }

    long proposed(String merchantKey) {
        return jdbc.queryForObject("""
                SELECT s.id FROM subscription s JOIN merchant m ON m.id = s.merchant_id
                WHERE m.key = ? AND s.state = 'PROPOSED'""", Long.class, merchantKey);
    }

    Subscription sub(long id) {
        return subscriptions.find(id).orElseThrow();
    }

    Status status(long id) {
        return recurring.overview(Optional.empty(), "RON").groups().stream().flatMap(g -> g.items().stream())
                .filter(i -> i.id() == id).map(Item::status).findFirst().orElseThrow();
    }

    Optional<ReviewCard> card(long subscriptionId, ReviewCard.Kind kind) {
        return http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == kind && subscriptionId == c.subscriptionId()).findFirst();
    }

    HttpStatus answer(long alertId, String action) {
        return HttpStatus.valueOf(http.postForEntity("/api/review/alerts/" + alertId, Map.of("action", action), String.class)
                .getStatusCode().value());
    }

    long linked(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE subscription_id = ?", Long.class, id);
    }

    @Test
    void theNextChargeLinksAndMovesThePredictionOn() throws Exception {
        charges("NETFLIX.COM", "-49.99", "2026-08-14");

        assertThat(linked(netflix)).isEqualTo(8);
        assertThat(sub(netflix).lastSeen()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(sub(netflix).nextExpectedDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(status(netflix)).isEqualTo(Status.ACTIVE);
        assertThat(card(netflix, ReviewCard.Kind.PRICE_CHANGE)).isEmpty();
    }

    @Nested
    class PriceChange {

        @Test
        void aNewPriceIsAskedAndGotItMakesItTheExpectedOne() throws Exception {
            charges("NETFLIX.COM", "-59.99", "2026-08-15");

            assertThat(status(netflix)).isEqualTo(Status.PRICE_CHANGED);
            var card = card(netflix, ReviewCard.Kind.PRICE_CHANGE).orElseThrow();
            assertThat(card.previousAmountMinor()).isEqualTo(4999);
            assertThat(card.newAmountMinor()).isEqualTo(5999);
            assertThat(card.key()).isEqualTo("alert:" + card.alertId());

            assertThat(answer(card.alertId(), "GOT_IT")).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(sub(netflix).expectedAmountMinor()).isEqualTo(5999);
            assertThat(status(netflix)).isEqualTo(Status.ACTIVE);
            charges("NETFLIX.COM", "-59.99", "2026-09-15");
            assertThat(card(netflix, ReviewCard.Kind.PRICE_CHANGE)).isEmpty();
            assertThat(answer(card.alertId(), "GOT_IT")).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void aBigRiseStaysTheSameSubscriptionNotANewProposal() throws Exception {
            // 69.99 is 40% up: outside the detector's 25% band, within the 50% a confirmed subscription follows.
            charges("NETFLIX.COM", "-69.99", "2026-08-15", "2026-09-15", "2026-10-15");
            subscriptions.refresh(LocalDate.of(2026, 10, 20));

            assertThat(linked(netflix)).isEqualTo(10);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM subscription s JOIN merchant m ON m.id = s.merchant_id WHERE m.key = 'NETFLIX'""",
                    Long.class)).isEqualTo(1);
            assertThat(card(netflix, ReviewCard.Kind.PRICE_CHANGE).orElseThrow().newAmountMinor()).isEqualTo(6999);
        }

        @Test
        void markEndedEndsIt() throws Exception {
            charges("NETFLIX.COM", "-59.99", "2026-08-15");

            assertThat(answer(card(netflix, ReviewCard.Kind.PRICE_CHANGE).orElseThrow().alertId(), "END"))
                    .isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(sub(netflix).state()).isEqualTo(State.ENDED);
            assertThat(sub(netflix).endedBy()).isEqualTo(EndedBy.USER);
        }
    }

    @Nested
    class Missed {

        @Test
        void nothingByTheDeadlineIsAskedAndStillActiveSkipsThePeriod() {
            subscriptions.refresh(LocalDate.of(2026, 8, 9)); // deadline day: not missed yet
            assertThat(card(gym, ReviewCard.Kind.MISSED_CHARGE)).isEmpty();

            subscriptions.refresh(LocalDate.of(2026, 8, 10));

            assertThat(status(gym)).isEqualTo(Status.MISSED);
            var card = card(gym, ReviewCard.Kind.MISSED_CHARGE).orElseThrow();
            assertThat(card.dueDate()).isEqualTo(LocalDate.of(2026, 8, 3));
            assertThat(card.affectedMinor()).isEqualTo(25_000);

            assertThat(answer(card.alertId(), "STILL_ACTIVE")).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(sub(gym).nextExpectedDate()).isEqualTo(LocalDate.of(2026, 9, 3));
            subscriptions.refresh(LocalDate.of(2026, 8, 20));
            assertThat(card(gym, ReviewCard.Kind.MISSED_CHARGE)).isEmpty();
            assertThat(status(gym)).isEqualTo(Status.ACTIVE);
        }

        @Test
        void aLateChargeAnswersItByItself() throws Exception {
            subscriptions.refresh(LocalDate.of(2026, 8, 10));

            charges("WORLD CLASS", "-250.00", "2026-08-08"); // in the last statement, within the grace days

            assertThat(card(gym, ReviewCard.Kind.MISSED_CHARGE)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT resolution FROM subscription_alert WHERE subscription_id = ?",
                    String.class, gym)).isEqualTo("CHARGED");
            assertThat(sub(gym).nextExpectedDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        }

        @Test
        void twoMissedInARowEndItUntilChargesResume() throws Exception {
            subscriptions.refresh(LocalDate.of(2026, 9, 10)); // 3 Aug and 3 Sep both missed

            assertThat(sub(gym).state()).isEqualTo(State.ENDED);
            assertThat(sub(gym).endedBy()).isEqualTo(EndedBy.SYSTEM);
            assertThat(card(gym, ReviewCard.Kind.MISSED_CHARGE)).isEmpty();

            charges("WORLD CLASS", "-250.00", "2026-10-05");

            assertThat(sub(gym).state()).isEqualTo(State.CONFIRMED);
            assertThat(sub(gym).lastSeen()).isEqualTo(LocalDate.of(2026, 10, 5));
        }

        @Test
        void cancelledEndsItForGood() throws Exception {
            subscriptions.refresh(LocalDate.of(2026, 8, 10));

            answer(card(gym, ReviewCard.Kind.MISSED_CHARGE).orElseThrow().alertId(), "CANCELLED");

            assertThat(sub(gym).state()).isEqualTo(State.ENDED);
            assertThat(sub(gym).endedBy()).isEqualTo(EndedBy.USER);
            charges("WORLD CLASS", "-250.00", "2026-09-03");
            assertThat(sub(gym).state()).isEqualTo(State.ENDED);
        }

        @Test
        void answersMustFitTheQuestion() {
            subscriptions.refresh(LocalDate.of(2026, 8, 10));
            long alert = card(gym, ReviewCard.Kind.MISSED_CHARGE).orElseThrow().alertId();

            assertThat(answer(alert, "GOT_IT")).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(answer(999_999, "STILL_ACTIVE")).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(http.postForEntity("/api/review/alerts/" + alert, Map.of(), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void confirmingWithAnotherCadenceTakesItsAnchorFromTheLatestCharge() {
        long emag = subscriptions.confirm(proposed("EMAG"), new Edits(null, Cadence.YEARLY, null)).id();

        var s = sub(emag);
        assertThat(s.cadence()).isEqualTo(Cadence.YEARLY);
        assertThat(s.anchorMonth()).isEqualTo(6);
        assertThat(s.anchorDay()).isEqualTo(5);
        assertThat(s.nextExpectedDate()).isEqualTo(LocalDate.of(2027, 6, 5));
    }
}
