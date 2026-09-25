package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.Candidate.Strength;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP4.1 on imported data: the seeded subscriptions are found, and nothing else is. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RecurrenceServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 7, 20);

    @Autowired UploadService uploads;
    @Autowired RecurrenceService recurrence;
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
    }

    void load(long accountId, String csv) throws Exception {
        uploads.importOne("ledger.csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(accountId), Optional.empty());
    }

    Map<String, Candidate> byMerchant(List<Candidate> candidates) {
        return candidates.stream().collect(Collectors.toMap(
                c -> jdbc.queryForObject("SELECT key FROM merchant WHERE id = ?", String.class, c.merchantId()),
                Function.identity()));
    }

    @Test
    void findsTheSeededSubscriptionsAndNothingElse() throws Exception {
        load(account, RecurringFixtures.LEDGER);

        var found = new java.util.HashMap<>(byMerchant(recurrence.detect(TODAY)));

        assertThat(found).containsOnlyKeys("NETFLIX", "ENEL", "ORANGE", "WORLD CLASS", "EMAG", "SALARIU ACME SRL");
        // CP6.5: the salary (money in, category Income) is recurring income, not a subscription.
        var salary = found.remove("SALARIU ACME SRL");
        assertThat(salary.direction()).isEqualTo(dev.costinfl.outflow.category.CategoryRule.Direction.IN);
        assertThat(salary.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(salary.anchorDay()).isEqualTo(25);
        assertThat(salary.expectedAmountMinor()).isEqualTo(500_000);
        assertThat(salary.occurrences()).isEqualTo(4);
        assertThat(found.values()).allSatisfy(c -> {
            assertThat(c.direction()).isEqualTo(dev.costinfl.outflow.category.CategoryRule.Direction.OUT);
            assertThat(c.cadence()).isEqualTo(Cadence.MONTHLY);
            assertThat(c.strength()).isEqualTo(Strength.PROPOSED);
            assertThat(c.accountId()).isEqualTo(account);
        });
        assertThat(found.get("NETFLIX").amountKind()).isEqualTo(AmountKind.FIXED);
        assertThat(found.get("NETFLIX").expectedAmountMinor()).isEqualTo(4999);
        assertThat(found.get("ENEL").amountKind()).isEqualTo(AmountKind.VARIABLE);
        assertThat(found.get("ORANGE").anchorDay()).isEqualTo(31);
        assertThat(found.get("ORANGE").score().interval()).isEqualTo(1);
        assertThat(found.get("WORLD CLASS").score().interval()).isEqualTo(0.8);
        assertThat(found.get("EMAG").expectedAmountMinor()).isEqualTo(2999);
        assertThat(found.get("EMAG").occurrences()).isEqualTo(6);
    }

    @Test
    void accountsAreSeparateStreams() throws Exception {
        long other = ImportFixtures.newAccount(jdbc, "Card");
        load(account, """
                Date,Description,Amount,Currency
                2026-04-15,NETFLIX.COM,-49.99,RON
                2026-05-15,NETFLIX.COM,-49.99,RON
                """);
        load(other, """
                Date,Description,Amount,Currency
                2026-06-15,NETFLIX.COM,-49.99,RON
                """);

        // Three monthly charges in total, but no account has three.
        assertThat(recurrence.detect(TODAY)).isEmpty();
    }
}
