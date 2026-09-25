package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.review.ReviewCard;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.9: "remind me before next charge" (DESIGN: Recurring payments, row actions). Netflix (49.99 on the 15th, last
 * charged 15 Jul) and Orange (65.00 at month end) are confirmed; today starts as 13 August 2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ChargeRemindersTest.Clocks.class})
class ChargeRemindersTest {

    static final LocalDate TODAY = LocalDate.of(2026, 8, 13);

    /** A clock the tests move forward. */
    static final class MovableClock extends Clock {
        LocalDate day = TODAY;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return day.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired MovableClock clock;
    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long account;
    long netflix;
    long orange;

    @BeforeEach
    void setUp() throws Exception {
        clock.day = TODAY;
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load(RecurringFixtures.LEDGER);
        netflix = of("NETFLIX");
        orange = of("ORANGE");
        subscriptions.confirm(netflix, Edits.NONE);
        subscriptions.confirm(orange, Edits.NONE);
    }

    void load(String csv) throws Exception {
        uploads.importOne("l" + csv.hashCode() + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    long of(String merchantKey) {
        return jdbc.queryForObject("""
                SELECT s.id FROM subscription s JOIN merchant m ON m.id = s.merchant_id WHERE m.key = ?""",
                Long.class, merchantKey);
    }

    HttpStatus remind(long id, Integer days) {
        var body = new java.util.HashMap<String, Object>();
        body.put("daysBefore", days);
        return HttpStatus.valueOf(http.exchange("/api/subscriptions/" + id + "/reminder", HttpMethod.PUT,
                new HttpEntity<>(body), String.class).getStatusCode().value());
    }

    List<ReviewCard> reminders() {
        return http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == ReviewCard.Kind.UPCOMING_CHARGE).toList();
    }

    @Test
    void aReminderIsAQuestionFromNDaysBeforeTheChargeToItsDay() {
        assertThat(remind(netflix, 1)).isEqualTo(HttpStatus.OK);
        assertThat(reminders()).isEmpty(); // 15 Aug is 2 days away

        assertThat(remind(netflix, 3)).isEqualTo(HttpStatus.OK);
        var card = reminders().getFirst();
        assertThat(reminders()).hasSize(1);
        assertThat(card.subscriptionId()).isEqualTo(netflix);
        assertThat(card.dueDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(card.expectedAmountMinor()).isEqualTo(4999);
        assertThat(card.affectedMinor()).isEqualTo(4999);
        assertThat(card.key()).isEqualTo("reminder:" + netflix + ":2026-08-15");

        clock.day = LocalDate.of(2026, 8, 15);
        assertThat(reminders()).hasSize(1); // still on the day itself
        clock.day = LocalDate.of(2026, 8, 16);
        assertThat(reminders()).isEmpty();
        assertThat(subscriptions.find(netflix).orElseThrow().remindDaysBefore()).isEqualTo(3);
    }

    @Test
    void gotItAnswersThisChargeAndTheNextOneAsksAgain() throws Exception {
        remind(netflix, 3);

        assertThat(http.postForEntity("/api/review/reminders/" + netflix, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reminders()).isEmpty();

        // The charge arrives; the next one is due 15 September.
        clock.day = LocalDate.of(2026, 8, 20);
        load("""
                Date,Description,Amount,Currency
                2026-08-15,NETFLIX.COM,-49.99,RON
                """);
        assertThat(subscriptions.find(netflix).orElseThrow().nextExpectedDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(reminders()).isEmpty();
        clock.day = LocalDate.of(2026, 9, 12);
        assertThat(reminders()).extracting(ReviewCard::dueDate).containsExactly(LocalDate.of(2026, 9, 15));
    }

    @Test
    void remindersAreForConfirmedPaymentsAndOneToFourteenDays() {
        long proposed = of("ENEL");

        assertThat(remind(netflix, 0)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(remind(netflix, 15)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(remind(proposed, 3)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(remind(999_999, 3)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/review/reminders/" + netflix, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND); // no reminder set

        remind(netflix, 3);
        assertThat(recurringItem(netflix).get("remindDaysBefore")).isEqualTo(3);
        assertThat(remind(netflix, null)).isEqualTo(HttpStatus.OK);
        assertThat(subscriptions.find(netflix).orElseThrow().remindDaysBefore()).isNull();
        assertThat(reminders()).isEmpty();
        assertThat(recurringItem(netflix).get("remindDaysBefore")).isNull();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> recurringItem(long id) {
        Map<String, Object> o = http.getForObject("/api/subscriptions", Map.class);
        return ((List<Map<String, Object>>) o.get("groups")).stream()
                .flatMap(g -> ((List<Map<String, Object>>) g.get("items")).stream())
                .filter(i -> ((Number) i.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    @Test
    void theRemindersDownloadAsACalendarFile() {
        remind(netflix, 3);
        remind(orange, 1);

        var response = http.getForEntity("/api/subscriptions/reminders.ics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/calendar");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("outflow-reminders.ics");
        String ics = response.getBody();
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(3);
        assertThat(ics).contains("UID:subscription-" + netflix + "@outflow.local\r\n", "DTSTART;VALUE=DATE:20260815\r\n",
                "RRULE:FREQ=MONTHLY;BYMONTHDAY=15\r\n", "TRIGGER:-P3D\r\n", "charges 49.99 RON",
                "RRULE:FREQ=MONTHLY;BYMONTHDAY=28,29,30,31;BYSETPOS=-1\r\n", "TRIGGER:-P1D\r\n");
        assertThat(Arrays.stream(ics.split("\r\n"))).allSatisfy(l -> assertThat(l.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(75));
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void repeatRulesFollowTheCadence() {
        assertThat(ReminderService.rule(Cadence.WEEKLY, 3, null, LocalDate.of(2026, 8, 12))).isEqualTo("FREQ=WEEKLY;BYDAY=WE");
        assertThat(ReminderService.rule(Cadence.YEARLY, 10, 3, LocalDate.of(2027, 3, 10)))
                .isEqualTo("FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=10");
        // After the 28th: the last of those days that exists, so February still gets its reminder.
        assertThat(ReminderService.rule(Cadence.MONTHLY, 31, null, LocalDate.of(2026, 9, 30)))
                .isEqualTo("FREQ=MONTHLY;BYMONTHDAY=28,29,30,31;BYSETPOS=-1");
        assertThat(ReminderService.rule(Cadence.MONTHLY, 30, null, LocalDate.of(2026, 9, 30)))
                .isEqualTo("FREQ=MONTHLY;BYMONTHDAY=28,29,30;BYSETPOS=-1");
        assertThat(ReminderService.rule(Cadence.MONTHLY, 28, null, LocalDate.of(2026, 9, 28)))
                .isEqualTo("FREQ=MONTHLY;BYMONTHDAY=28");
        assertThat(ReminderService.escape("A, B; C\\D")).isEqualTo("A\\, B\\; C\\\\D");
    }
}
