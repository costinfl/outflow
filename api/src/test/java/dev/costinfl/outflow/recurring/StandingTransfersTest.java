package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.RecurringOverview.StandingTransfer;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.review.ReviewService;
import dev.costinfl.outflow.txn.Slice;
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
 * CP6.6: standing transfers (DESIGN: Recurring payments, "Standing transfers (savings, own accounts — shown but
 * excluded from the total)"). Main moves 1,000 to Savings on the 1st (Jan–Jul, paired with Savings' side) and topped up
 * Revolut with 200 on the 20th until April. As of 20 July 2026 the first is active, the second has stopped.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StandingTransfersTest.FixedClock.class})
class StandingTransfersTest {

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
    @Autowired RecurringService recurring;
    @Autowired SubscriptionService subscriptions;
    @Autowired ReviewService review;
    @Autowired JdbcTemplate jdbc;

    long main;
    long savings;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        savings = ImportFixtures.newAccount(jdbc, "Savings");
        var mainRows = new StringBuilder("Date,Description,Amount,Currency\n");
        var savingsRows = new StringBuilder("Date,Description,Amount,Currency\n");
        for (int m = 1; m <= 7; m++) {
            String first = "2026-%02d-01".formatted(m);
            mainRows.append(first).append(",TRANSFER CATRE CONT ECONOMII,-1000.00,RON\n");
            savingsRows.append(first).append(",INCASARE DIN CONT CURENT,1000.00,RON\n");
            if (m <= 4) {
                mainRows.append("2026-%02d-20".formatted(m)).append(",REVOLUT TOP-UP,-200.00,RON\n");
            }
            if (m <= 6) {
                mainRows.append("2026-%02d-15".formatted(m)).append(",NETFLIX.COM,-49.99,RON\n");
            }
        }
        load(main, mainRows.toString());
        load(savings, savingsRows.toString());
    }

    void load(long account, String csv) throws Exception {
        uploads.importOne("a" + account + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    StandingTransfer named(List<StandingTransfer> list, String merchantKey) {
        long merchant = jdbc.queryForObject("SELECT id FROM merchant WHERE key = ?", Long.class, merchantKey);
        return list.stream().filter(s -> s.merchantId() == merchant).findFirst().orElseThrow();
    }

    String key(long merchantId) {
        return jdbc.queryForObject("SELECT key FROM merchant WHERE id = ?", String.class, merchantId);
    }

    @Test
    void recurringTransfersAreListedWithWhereTheyGo() {
        var o = recurring.overview(Optional.empty(), "RON");

        assertThat(o.standingTransfers()).hasSize(2);
        var savingsTransfer = o.standingTransfers().getFirst();
        assertThat(key(savingsTransfer.merchantId())).isEqualTo("CONT ECONOMII");
        assertThat(savingsTransfer.toAccountName()).isEqualTo("Savings");
        assertThat(savingsTransfer.accountId()).isEqualTo(main);
        assertThat(savingsTransfer.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(savingsTransfer.expectedAmountMinor()).isEqualTo(100_000);
        assertThat(savingsTransfer.monthlyMinor()).isEqualTo(100_000);
        assertThat(savingsTransfer.occurrences()).isEqualTo(7);
        assertThat(savingsTransfer.lastDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(savingsTransfer.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(savingsTransfer.active()).isTrue();

        // Revolut top-ups (Transfer category, not paired): no destination, and stopped after April.
        var revolut = o.standingTransfers().get(1);
        assertThat(key(revolut.merchantId())).startsWith("REVOLUT");
        assertThat(revolut.toAccountName()).isNull();
        assertThat(revolut.active()).isFalse();
        assertThat(revolut.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 5, 20));

        assertThat(o.standingMonthlyMinor()).isEqualTo(100_000);
    }

    @Test
    void standingTransfersAreNeverCommitmentsNorQuestions() {
        subscriptions.list().forEach(s -> subscriptions.confirm(s.id(), Edits.NONE));

        var o = recurring.overview(Optional.empty(), "RON");

        assertThat(subscriptions.list()).extracting(s -> key(s.merchantId())).containsExactly("NETFLIX");
        assertThat(o.monthlyMinor()).isEqualTo(4999);
        assertThat(o.countedCount()).isEqualTo(1);
        assertThat(o.groups()).flatExtracting(RecurringOverview.Group::items)
                .extracting(RecurringOverview.Item::name).doesNotContain(o.standingTransfers().getFirst().name());
        assertThat(review.inbox().cards()).noneMatch(c -> key(c.merchantId()).equals("CONT ECONOMII"));
    }

    @Test
    void theAccountsFilterShowsOnlyTransfersOutOfTheSelectedAccounts() {
        assertThat(recurring.overview(Optional.empty(), new Slice("RON", List.of(savings))).standingTransfers()).isEmpty();
        assertThat(recurring.overview(Optional.empty(), new Slice("RON", List.of(main))).standingTransfers()).hasSize(2);
    }

    @Test
    void aPastMonthSeesOnlyWhatWasBookedByItsEnd() {
        var march = recurring.overview(Optional.of(YearMonth.of(2026, 3)), "RON");

        var savingsTransfer = named(march.standingTransfers(), "CONT ECONOMII");
        assertThat(savingsTransfer.occurrences()).isEqualTo(3);
        assertThat(savingsTransfer.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(march.standingTransfers()).allSatisfy(s -> assertThat(s.active()).isTrue());
        assertThat(march.standingMonthlyMinor()).isEqualTo(100_000 + 20_000);
    }
}
