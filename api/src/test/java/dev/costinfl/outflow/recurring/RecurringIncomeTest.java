package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.InsightService;
import dev.costinfl.outflow.recurring.RecurringOverview.GroupKind;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.review.ReviewCard;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import dev.costinfl.outflow.review.ReviewService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.5: recurring income. A salary paid in two parts (an advance of 4,000 around the 25th, the rest around the 10th,
 * 4,300–4,520) is two monthly income streams; money in without an Income category is not income; payments and income
 * never mix. Uploads run as of 5 July 2026: next parts due 10 and 25 July.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RecurringIncomeTest.FixedClock.class})
class RecurringIncomeTest {

    static final LocalDate TODAY = LocalDate.of(2026, 7, 5);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        }
    }

    static final String LEDGER = """
            Date,Description,Amount,Currency
            2026-01-09,INCASARE SALARIU ACME SRL,4300.00,RON
            2026-01-26,INCASARE SALARIU ACME SRL,4000.00,RON
            2026-02-10,INCASARE SALARIU ACME SRL,4350.00,RON
            2026-02-25,INCASARE SALARIU ACME SRL,4000.00,RON
            2026-03-10,INCASARE SALARIU ACME SRL,4300.00,RON
            2026-03-25,INCASARE SALARIU ACME SRL,4000.00,RON
            2026-04-09,INCASARE SALARIU ACME SRL,4520.00,RON
            2026-04-24,INCASARE SALARIU ACME SRL,4000.00,RON
            2026-05-11,INCASARE SALARIU ACME SRL,4300.00,RON
            2026-05-25,INCASARE SALARIU ACME SRL,4000.00,RON
            2026-06-10,INCASARE SALARIU ACME SRL,4410.00,RON
            2026-06-25,INCASARE SALARIU ACME SRL,4000.00,RON
            2026-01-15,INCASARE ION POPESCU,500.00,RON
            2026-02-15,INCASARE ION POPESCU,500.00,RON
            2026-03-16,INCASARE ION POPESCU,500.00,RON
            2026-04-15,INCASARE ION POPESCU,500.00,RON
            2026-05-15,INCASARE ION POPESCU,500.00,RON
            2026-06-15,INCASARE ION POPESCU,500.00,RON
            2026-01-15,NETFLIX.COM,-49.99,RON
            2026-02-16,NETFLIX.COM,-49.99,RON
            2026-03-16,NETFLIX.COM,-49.99,RON
            2026-04-15,NETFLIX.COM,-49.99,RON
            2026-05-15,NETFLIX.COM,-49.99,RON
            2026-06-15,NETFLIX.COM,-49.99,RON
            """;

    @Autowired UploadService uploads;
    @Autowired SubscriptionService subscriptions;
    @Autowired RecurringService recurring;
    @Autowired ReviewService review;
    @Autowired InsightService insights;
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load(LEDGER);
    }

    void load(String csv) throws Exception {
        uploads.importOne("ledger-" + csv.hashCode() + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    List<Subscription> income() {
        return subscriptions.list().stream().filter(s -> s.direction() == Direction.IN).toList();
    }

    Subscription part(int anchorDay) {
        return income().stream().filter(s -> s.anchorDay() == anchorDay).findFirst().orElseThrow();
    }

    Subscription netflix() {
        return subscriptions.list().stream().filter(s -> s.direction() == Direction.OUT).findFirst().orElseThrow();
    }

    Long subscriptionOf(String date) {
        return jdbc.queryForObject("""
                SELECT subscription_id FROM transaction WHERE booking_date = ?::date AND description_raw LIKE '%SALARIU%'""",
                Long.class, date);
    }

    void confirmAll() {
        subscriptions.list().forEach(s -> subscriptions.confirm(s.id(), Edits.NONE));
    }

    @Test
    void aSalaryInTwoPartsIsTwoMonthlyIncomeStreams() {
        assertThat(income()).hasSize(2);
        assertThat(income()).extracting(Subscription::anchorDay).containsExactlyInAnyOrder(10, 25);
        assertThat(income()).allSatisfy(s -> assertThat(s.cadence()).isEqualTo(Cadence.MONTHLY));

        var advance = part(25);
        assertThat(advance.expectedAmountMinor()).isEqualTo(400_000);
        assertThat(advance.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 7, 25));
        var rest = part(10);
        assertThat(rest.expectedAmountMinor()).isEqualTo(441_000); // median of the last three: 4,520 · 4,300 · 4,410
        assertThat(rest.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE subscription_id = ?", Long.class,
                advance.id())).isEqualTo(6);
        assertThat(subscriptionOf("2026-04-09")).isEqualTo(rest.id());

        // Money in without an Income category (a person paying 500 every month) is not income; Netflix is a payment.
        assertThat(subscriptions.list()).hasSize(3);
        assertThat(netflix().expectedAmountMinor()).isEqualTo(4999);
    }

    @Test
    void theReviewInboxAsksAboutIncomeAsIncome() {
        var cards = review.inbox().cards().stream().filter(c -> c.kind() == Kind.SUBSCRIPTION).toList();

        assertThat(cards).filteredOn(c -> c.direction() == Direction.IN).extracting(ReviewCard::subscriptionId)
                .containsExactlyInAnyOrder(part(10).id(), part(25).id());
        assertThat(cards).filteredOn(c -> c.direction() == Direction.OUT).extracting(ReviewCard::subscriptionId)
                .containsExactly(netflix().id());
        var advance = cards.stream().filter(c -> c.subscriptionId() == part(25).id()).findFirst().orElseThrow();
        assertThat(advance.affectedMinor()).isEqualTo(6 * 400_000);
    }

    @Test
    void confirmedIncomeIsItsOwnGroupAndNeverACommitment() {
        confirmAll();

        var o = recurring.overview(Optional.empty(), "RON");

        assertThat(o.groups()).extracting(RecurringOverview.Group::kind)
                .containsExactly(GroupKind.SUBSCRIPTIONS, GroupKind.INCOME);
        var income = o.groups().get(1);
        assertThat(income.items()).extracting(RecurringOverview.Item::id).containsExactly(part(10).id(), part(25).id());
        assertThat(income.monthlyMinor()).isEqualTo(441_000 + 400_000);
        assertThat(o.incomeMonthlyMinor()).isEqualTo(441_000 + 400_000);
        assertThat(o.monthlyMinor()).isEqualTo(4999);
        assertThat(o.yearlyMinor()).isEqualTo(12 * 4999);
        assertThat(o.countedCount()).isEqualTo(1);
        // The home "Committed every month" figure is payments only.
        var june = insights.month(YearMonth.of(2026, 6), "RON");
        assertThat(june.committed().monthlyMinor()).isEqualTo(4999);
        assertThat(june.committed().count()).isEqualTo(1);
    }

    /** CP6.8: the home screen's recurring income is the Recurring screen's, for the same month. */
    @Test
    void theHomeScreenShowsConfirmedRecurringIncome() {
        assertThat(insights.month(YearMonth.of(2026, 6), "RON").committed().incomeMonthlyMinor()).isZero();

        confirmAll();

        var june = insights.month(YearMonth.of(2026, 6), "RON").committed();
        assertThat(june.incomeMonthlyMinor()).isEqualTo(441_000 + 400_000);
        assertThat(june.incomeCount()).isEqualTo(2);
        assertThat(june.incomeMonthlyMinor())
                .isEqualTo(recurring.overview(Optional.of(YearMonth.of(2026, 6)), "RON").incomeMonthlyMinor());
        assertThat(june.monthlyMinor()).isEqualTo(4999);
        // Before the salary started, nothing was expected.
        var december = insights.month(YearMonth.of(2025, 12), "RON").committed();
        assertThat(december.incomeMonthlyMinor()).isZero();
        assertThat(december.incomeCount()).isZero();
    }

    @Test
    void newPaymentsJoinThePartTheyAreDueFor() throws Exception {
        confirmAll();

        load("""
                Date,Description,Amount,Currency
                2026-07-10,INCASARE SALARIU ACME SRL,4450.00,RON
                2026-07-24,INCASARE SALARIU ACME SRL,4000.00,RON
                """);

        assertThat(subscriptionOf("2026-07-10")).isEqualTo(part(10).id());
        assertThat(subscriptionOf("2026-07-24")).isEqualTo(part(25).id());
        assertThat(part(10).nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(part(25).nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(income()).hasSize(2);
    }

    @Test
    void aMissedSalaryPartIsAQuestion() {
        confirmAll();

        subscriptions.refresh(LocalDate.of(2026, 7, 20)); // the 10th's part is due 10 July: late after 16 July

        var missed = review.inbox().cards().stream().filter(c -> c.kind() == Kind.MISSED_CHARGE).toList();
        assertThat(missed).hasSize(1);
        assertThat(missed.getFirst().subscriptionId()).isEqualTo(part(10).id());
        assertThat(missed.getFirst().direction()).isEqualTo(Direction.IN);
        assertThat(missed.getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 7, 10));
    }

    @Test
    void aRaiseIsAPriceChangeWithPositiveAmounts() throws Exception {
        confirmAll();

        load("""
                Date,Description,Amount,Currency
                2026-07-10,INCASARE SALARIU ACME SRL,5000.00,RON
                """);

        assertThat(subscriptionOf("2026-07-10")).isEqualTo(part(10).id());
        var change = review.inbox().cards().stream().filter(c -> c.kind() == Kind.PRICE_CHANGE).toList();
        assertThat(change).hasSize(1);
        assertThat(change.getFirst().direction()).isEqualTo(Direction.IN);
        assertThat(change.getFirst().previousAmountMinor()).isEqualTo(441_000);
        assertThat(change.getFirst().newAmountMinor()).isEqualTo(500_000);
    }

    @Test
    void aRejectedPartStaysRejectedAndTheOtherPartStays() {
        subscriptions.reject(part(25).id());

        assertThat(jdbc.queryForObject("SELECT direction FROM subscription_rejection", String.class)).isEqualTo("IN");
        var result = subscriptions.refreshNow();

        assertThat(result.proposed()).isZero();
        assertThat(income()).extracting(Subscription::state)
                .containsExactlyInAnyOrder(Subscription.State.REJECTED, Subscription.State.PROPOSED);
    }

    @Test
    void refreshingAgainChangesNothing() {
        var before = subscriptions.list();

        var result = subscriptions.refresh(TODAY);

        assertThat(result.proposed()).isZero();
        assertThat(result.dropped()).isZero();
        assertThat(subscriptions.list()).isEqualTo(before);
    }
}
