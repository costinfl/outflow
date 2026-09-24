package dev.costinfl.outflow.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.Subscription.EndedBy;
import dev.costinfl.outflow.recurring.Subscription.State;
import dev.costinfl.outflow.recurring.SubscriptionService.TransitionException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
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

/** CP4.2: the subscription lifecycle. The user decides; automation never overrides a decision or re-proposes a rejection. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SubscriptionServiceTest.FixedClock.class})
class SubscriptionServiceTest {

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
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
        load(RecurringFixtures.LEDGER); // the upload pipeline refreshes subscriptions
    }

    void load(String csv) throws Exception {
        uploads.importOne("ledger-" + csv.hashCode() + ".csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    void loadCharges(String description, String amount, String... dates) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\n");
        for (String d : dates) {
            csv.append(d).append(',').append(description).append(',').append(amount).append(",RON\n");
        }
        load(csv.toString());
    }

    String key(Subscription s) {
        return jdbc.queryForObject("SELECT key FROM merchant WHERE id = ?", String.class, s.merchantId());
    }

    List<Subscription> of(String merchantKey) {
        return subscriptions.list().stream().filter(s -> key(s).equals(merchantKey)).toList();
    }

    Subscription live(String merchantKey) {
        var rows = of(merchantKey).stream().filter(s -> s.state() != State.REJECTED).toList();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    long linked(long subscriptionId) {
        return jdbc.queryForObject("SELECT count(*) FROM transaction WHERE subscription_id = ?", Long.class, subscriptionId);
    }

    void recategorizeAsTransfer(String merchantKey) {
        jdbc.update("""
                UPDATE transaction SET category_id = 16, category_source = 'USER', category_confidence = 1
                WHERE merchant_id = (SELECT id FROM merchant WHERE key = ?)""", merchantKey);
    }

    @Test
    void uploadProposesDetectedSubscriptionsAndLinksTheirCharges() {
        var all = subscriptions.list();

        assertThat(all).extracting(this::key).containsExactlyInAnyOrder("NETFLIX", "ENEL", "ORANGE", "WORLD CLASS", "EMAG");
        assertThat(all).allSatisfy(s -> assertThat(s.state()).isEqualTo(State.PROPOSED));
        var netflix = live("NETFLIX");
        assertThat(netflix.name()).isEqualTo(jdbc.queryForObject(
                "SELECT display_name FROM merchant WHERE key = 'NETFLIX'", String.class));
        assertThat(netflix.expectedAmountMinor()).isEqualTo(4999);
        assertThat(netflix.confidence()).isEqualByComparingTo("1.000");
        assertThat(netflix.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(linked(netflix.id())).isEqualTo(7);
        // eMAG: the six plan charges are linked, the two one-off purchases are not.
        assertThat(linked(live("EMAG").id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                WHERE m.key = 'EMAG' AND t.subscription_id IS NULL""", Long.class)).isEqualTo(2);
    }

    @Test
    void refreshIsIdempotent() {
        var before = subscriptions.list();

        var result = subscriptions.refresh(TODAY);

        assertThat(result.proposed()).isZero();
        assertThat(result.dropped()).isZero();
        assertThat(result.linked()).isZero();
        assertThat(subscriptions.list()).isEqualTo(before);
    }

    @Test
    void rejectedIsNeverProposedAgain() throws Exception {
        var rejected = subscriptions.reject(live("NETFLIX").id());

        assertThat(rejected.state()).isEqualTo(State.REJECTED);
        assertThat(linked(rejected.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_rejection", Long.class)).isEqualTo(1);

        subscriptions.refresh(TODAY);
        loadCharges("NETFLIX.COM", "-49.99", "2026-08-15", "2026-09-15");
        subscriptions.refresh(LocalDate.of(2026, 9, 20));

        assertThat(of("NETFLIX")).extracting(Subscription::state).containsExactly(State.REJECTED);
    }

    @Test
    void aMaterialPriceChangeIsProposedAgain() throws Exception {
        subscriptions.reject(live("NETFLIX").id());

        // 79.99 is 60% above the rejected 49.99: a different plan, so it is a new question.
        loadCharges("NETFLIX.COM", "-79.99", "2026-08-15", "2026-09-15", "2026-10-15");
        subscriptions.refresh(LocalDate.of(2026, 10, 20));

        var proposed = live("NETFLIX");
        assertThat(proposed.state()).isEqualTo(State.PROPOSED);
        assertThat(proposed.expectedAmountMinor()).isEqualTo(7999);
    }

    @Test
    void confirmKeepsTheUsersFieldsAndLinksNewCharges() throws Exception {
        var netflix = subscriptions.confirm(live("NETFLIX").id(), new Edits("Netflix Premium", null, 5500L));
        assertThat(netflix.state()).isEqualTo(State.CONFIRMED);

        loadCharges("NETFLIX.COM", "-49.99", "2026-08-15");
        subscriptions.refresh(LocalDate.of(2026, 8, 20));

        var after = live("NETFLIX");
        assertThat(after.id()).isEqualTo(netflix.id());
        assertThat(after.state()).isEqualTo(State.CONFIRMED);
        assertThat(after.name()).isEqualTo("Netflix Premium");
        assertThat(after.expectedAmountMinor()).isEqualTo(5500);
        assertThat(after.cadence()).isEqualTo(Cadence.MONTHLY);
        assertThat(after.lastSeen()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(after.nextExpectedDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(linked(after.id())).isEqualTo(8);
    }

    @Test
    void aConfirmedSubscriptionStaysWhenTheDetectorLosesIt() {
        var gym = subscriptions.confirm(live("WORLD CLASS").id(), Edits.NONE);
        recategorizeAsTransfer("WORLD CLASS");

        subscriptions.refresh(TODAY);

        assertThat(subscriptions.find(gym.id()).orElseThrow().state()).isEqualTo(State.CONFIRMED);
        assertThat(linked(gym.id())).isEqualTo(6);
    }

    @Test
    void proposalsNoLongerDetectedAreDropped() {
        long enel = live("ENEL").id();
        recategorizeAsTransfer("ENEL");

        var result = subscriptions.refresh(TODAY);

        assertThat(result.dropped()).isEqualTo(1);
        assertThat(subscriptions.find(enel)).isEmpty();
        assertThat(linked(enel)).isZero();
    }

    @Test
    void endedByTheUserStaysEnded() throws Exception {
        long id = subscriptions.confirm(live("NETFLIX").id(), Edits.NONE).id();
        var ended = subscriptions.end(id);
        assertThat(ended.state()).isEqualTo(State.ENDED);
        assertThat(ended.endedBy()).isEqualTo(EndedBy.USER);
        assertThat(ended.nextExpectedDate()).isNull();

        loadCharges("NETFLIX.COM", "-49.99", "2026-08-15");
        subscriptions.refresh(LocalDate.of(2026, 8, 20));

        assertThat(of("NETFLIX")).singleElement().satisfies(s -> assertThat(s.state()).isEqualTo(State.ENDED));
        assertThat(linked(id)).isEqualTo(7);
    }

    @Test
    void endedByTheSystemResumesWhenChargesResume() throws Exception {
        long id = subscriptions.confirm(live("NETFLIX").id(), Edits.NONE).id();
        jdbc.update("UPDATE subscription SET state = 'ENDED', ended_by = 'SYSTEM' WHERE id = ?", id);

        loadCharges("NETFLIX.COM", "-49.99", "2026-08-15");
        subscriptions.refresh(LocalDate.of(2026, 8, 20));

        var resumed = subscriptions.find(id).orElseThrow();
        assertThat(resumed.state()).isEqualTo(State.CONFIRMED);
        assertThat(resumed.endedBy()).isNull();
        assertThat(resumed.lastSeen()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(linked(id)).isEqualTo(8);
    }

    @Test
    void onlyLifecycleTransitionsAreAllowed() {
        long netflix = live("NETFLIX").id();
        long enel = live("ENEL").id();
        subscriptions.confirm(netflix, Edits.NONE);
        subscriptions.reject(enel);

        assertThatThrownBy(() -> subscriptions.reject(netflix)).isInstanceOf(TransitionException.class);
        assertThatThrownBy(() -> subscriptions.confirm(enel, Edits.NONE)).isInstanceOf(TransitionException.class);
        assertThatThrownBy(() -> subscriptions.end(live("ORANGE").id())).isInstanceOf(TransitionException.class);
    }
}
