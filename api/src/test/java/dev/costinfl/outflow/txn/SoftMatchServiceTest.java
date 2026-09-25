package dev.costinfl.outflow.txn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.ImportService;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser;
import dev.costinfl.outflow.ingest.parse.csv.CsvProfileLoader;
import dev.costinfl.outflow.insight.InsightService;
import dev.costinfl.outflow.review.ReviewCard;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CP5.2: pending vs posted. A pending card payment that reappears posted with another date or final amount is
 * superseded (never deleted) and counts nowhere; ambiguous matches become review questions. March 2026.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SoftMatchServiceTest {

    static final String PROFILE = """
            id: status-test
            dateFormat: yyyy-MM-dd
            columns:
              bookingDate: Date
              amount: Amount
              currency: Currency
              description: [Description]
              status: Status
              pendingValues: [Pending]
            """;

    @Autowired ImportService imports;
    @Autowired UploadService pipeline;
    @Autowired SoftMatchService softMatches;
    @Autowired TransactionQueries transactions;
    @Autowired InsightService insights;
    @Autowired TransactionTemplate tx;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main;
    int files;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
    }

    /** Rows as "date,description,amount,Pending|Posted"; imports one file and runs the derived stages. */
    UploadService.Derived load(long account, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency,Status\n");
        for (String r : rows) {
            String[] f = r.split(",");
            csv.append(f[0]).append(',').append(f[1]).append(',').append(f[2]).append(",RON,").append(f[3]).append('\n');
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        var parser = new ConfigurableCsvParser(CsvProfileLoader.load(
                new ByteArrayInputStream(PROFILE.getBytes(StandardCharsets.UTF_8)), "test.yml"));
        var parsed = parser.parse(new ByteArrayInputStream(bytes));
        return tx.execute(s -> {
            imports.importParsed(account, "file" + (++files) + ".csv", bytes, "status-test", parsed);
            return pipeline.derive();
        });
    }

    Map<String, Object> row(String date, long amountMinor) {
        return jdbc.queryForMap("SELECT id, status, superseded_by, superseded_source FROM transaction "
                + "WHERE account_id = ? AND booking_date = ?::date AND amount_minor = ?", main, date, amountMinor);
    }

    long spentInMarch() {
        return insights.month(YearMonth.of(2026, 3), "RON").spentMinor();
    }

    @Test
    void anUnchangedPostedRowTurnsThePendingOnePosted() throws Exception {
        load(main, "2026-03-03,CUMPARARE POS EMAG,-120.00,Pending");
        assertThat(row("2026-03-03", -12000).get("status")).isEqualTo("PENDING");

        load(main, "2026-03-03,CUMPARARE POS EMAG,-120.00,Posted");

        assertThat(ImportFixtures.count(jdbc, "transaction", main)).isEqualTo(1);
        assertThat(row("2026-03-03", -12000).get("status")).isEqualTo("POSTED");
        assertThat(spentInMarch()).isEqualTo(12_000);
    }

    @Nested
    class AutoLink {

        @Test
        void aSingleMatchSupersedesThePendingRow() throws Exception {
            load(main, "2026-03-03,CUMPARARE POS EMAG,-120.00,Pending");
            assertThat(spentInMarch()).isEqualTo(12_000); // a reservation is money already spent

            var derived = load(main, "2026-03-05,CUMPARARE POS EMAG,-123.50,Posted"); // +2.9%, 2 days later

            assertThat(derived.softMatch().linked()).isEqualTo(1);
            var pending = row("2026-03-03", -12000);
            var posted = row("2026-03-05", -12350);
            assertThat(pending.get("superseded_by")).isEqualTo(posted.get("id"));
            assertThat(pending.get("superseded_source")).isEqualTo("AUTO");
            assertThat(spentInMarch()).isEqualTo(12_350);
            var list = transactions.list(TransactionFilter.month(YearMonth.of(2026, 3), "RON"));
            assertThat(list).extracting(TransactionView::amountMinor).containsExactly(-12350L);
            assertThat(list.getFirst().status()).isEqualTo("POSTED");
        }

        @Test
        void amountWithinFivePercentOrTwoUnitsAndDatesWithinFiveDays() {
            var pending = new SoftMatchService.Row(1, 1, 1, "RON", LocalDate.of(2026, 3, 3), -1000);
            assertThat(SoftMatchService.matches(pending, posted(-1190, 3))).isTrue(); // 1.90 RON: within 2 units
            assertThat(SoftMatchService.matches(pending, posted(-1201, 3))).isFalse(); // 2.01 RON and 20%
            var big = new SoftMatchService.Row(1, 1, 1, "RON", LocalDate.of(2026, 3, 3), -100_000);
            assertThat(SoftMatchService.matches(big, posted(-105_000, 3))).isTrue(); // exactly 5%
            assertThat(SoftMatchService.matches(big, posted(-105_001, 3))).isFalse();
            assertThat(SoftMatchService.matches(pending, posted(-1000, 8))).isTrue(); // 5 days
            assertThat(SoftMatchService.matches(pending, posted(-1000, 9))).isFalse(); // 6 days
            assertThat(SoftMatchService.matches(pending, posted(1000, 3))).isFalse(); // a refund is not the charge
            assertThat(SoftMatchService.twoUnits("JPY")).isEqualTo(2);
        }

        SoftMatchService.Row posted(long amount, int day) {
            return new SoftMatchService.Row(2, 1, 1, "RON", LocalDate.of(2026, 3, day), amount);
        }

        @Test
        void otherMerchantsAndAccountsNeverMatch() throws Exception {
            long card = ImportFixtures.newAccount(jdbc, "Card");
            load(main, "2026-03-03,CUMPARARE POS EMAG,-120.00,Pending");
            load(main, "2026-03-04,CUMPARARE POS ALTEX,-120.00,Posted");
            load(card, "2026-03-04,CUMPARARE POS EMAG,-120.00,Posted");

            assertThat(row("2026-03-03", -12000).get("superseded_by")).isNull();
        }

        @Test
        void aLaterCandidateTurnsAnAutoLinkIntoAQuestion() throws Exception {
            load(main, "2026-03-03,CUMPARARE POS EMAG,-120.00,Pending");
            load(main, "2026-03-04,CUMPARARE POS EMAG,-120.00,Posted");
            assertThat(row("2026-03-03", -12000).get("superseded_source")).isEqualTo("AUTO");

            var derived = load(main, "2026-03-06,CUMPARARE POS EMAG,-121.00,Posted");

            assertThat(row("2026-03-03", -12000).get("superseded_by")).isNull();
            assertThat(derived.softMatch().questions()).isEqualTo(2);
            assertThat(softMatches.openQuestions()).hasSize(2);
        }
    }

    @Nested
    class Questions {

        long pending, first, second;

        @BeforeEach
        void twoCandidates() throws Exception {
            load(main, "2026-03-03,CUMPARARE POS EMAG,-120.00,Pending");
            load(main, "2026-03-04,CUMPARARE POS EMAG,-120.00,Posted", "2026-03-05,CUMPARARE POS EMAG,-121.00,Posted");
            pending = (Long) row("2026-03-03", -12000).get("id");
            first = (Long) row("2026-03-04", -12000).get("id");
            second = (Long) row("2026-03-05", -12100).get("id");
        }

        long question(long posted) {
            return softMatches.openQuestions().stream().filter(q -> q.postedId() == posted).findFirst().orElseThrow().id();
        }

        @Test
        void severalCandidatesAreAskedNotGuessed() {
            assertThat(row("2026-03-03", -12000).get("superseded_by")).isNull();
            assertThat(spentInMarch()).isEqualTo(36_100); // undecided: everything still counts

            var cards = http.getForObject("/api/review", Inbox.class).cards().stream()
                    .filter(c -> c.kind() == ReviewCard.Kind.POSSIBLE_DUPLICATE).toList();
            assertThat(cards).hasSize(2);
            var c = cards.stream().filter(x -> x.postedDate().equals(LocalDate.of(2026, 3, 5))).findFirst().orElseThrow();
            assertThat(c.pendingDate()).isEqualTo(LocalDate.of(2026, 3, 3));
            assertThat(c.pendingAmountMinor()).isEqualTo(-12000);
            assertThat(c.postedAmountMinor()).isEqualTo(-12100);
            assertThat(c.affectedMinor()).isEqualTo(12000);
            assertThat(c.key()).isEqualTo("duplicate:" + c.duplicateId());
        }

        @Test
        void sameLinksForGoodAndAnswersTheOtherQuestion() throws Exception {
            long q = question(second);

            var r = http.postForEntity("/api/review/duplicates/" + q, Map.of("same", true), Void.class);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(row("2026-03-03", -12000)).containsEntry("superseded_by", second).containsEntry("superseded_source", "USER");
            assertThat(softMatches.openQuestions()).isEmpty();
            assertThat(spentInMarch()).isEqualTo(24_100);
            assertThat(http.postForEntity("/api/review/duplicates/" + q, Map.of("same", true), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT);

            // The user's link survives later uploads, even one that would make it ambiguous again.
            load(main, "2026-03-06,CUMPARARE POS EMAG,-120.50,Posted");
            assertThat(row("2026-03-03", -12000)).containsEntry("superseded_by", second).containsEntry("superseded_source", "USER");
        }

        @Test
        void differentIsRememberedAndTheRemainingMatchLinks() {
            http.postForEntity("/api/review/duplicates/" + question(first), Map.of("same", false), Void.class);

            // Only one candidate left, and it has only this pending row: linked automatically.
            assertThat(row("2026-03-03", -12000)).containsEntry("superseded_by", second).containsEntry("superseded_source", "AUTO");
            assertThat(jdbc.queryForObject("SELECT resolution FROM soft_match_review WHERE posted_transaction_id = ?",
                    String.class, first)).isEqualTo("DIFFERENT");
        }

        @Test
        void badAnswers() {
            assertThat(http.postForEntity("/api/review/duplicates/999999", Map.of("same", true), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(http.postForEntity("/api/review/duplicates/" + question(first), Map.of(), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void aSupersededPendingRowIsNotATransfer() throws Exception {
        long savings = ImportFixtures.newAccount(jdbc, "Savings");
        load(main, "2026-03-05,ORDIN PLATA STASH,-500.00,Pending");
        load(savings, "2026-03-09,INCASARE ORDIN PLATA,500.00,Posted");
        long pending = (Long) row("2026-03-05", -50000).get("id");
        assertThat(jdbc.queryForObject("SELECT out_transaction_id FROM transfer_pair", Long.class)).isEqualTo(pending);

        load(main, "2026-03-06,ORDIN PLATA STASH,-500.00,Posted");

        long posted = (Long) row("2026-03-06", -50000).get("id");
        assertThat(row("2026-03-05", -50000).get("superseded_by")).isEqualTo(posted);
        assertThat(jdbc.queryForList("SELECT out_transaction_id FROM transfer_pair", Long.class)).isEqualTo(List.of(posted));
        assertThat(spentInMarch()).isZero();
    }
}
