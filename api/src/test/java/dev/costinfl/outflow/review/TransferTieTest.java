package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.ingest.account.IbanHasher;
import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.insight.InsightService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import dev.costinfl.outflow.txn.TransferService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.12: "Which transfer is this?" (DESIGN: "ties go to review", spec question 20). 300 RON leaves Main on Monday
 * 2 March 2026 and 300 RON arrives on Tuesday in both Savings and Card: equally good partners, so nothing is paired and
 * the inbox asks. Synthetic IBANs (valid checksums, fake bank code TEST).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TransferTieTest {

    @Autowired UploadService uploads;
    @Autowired TransferService transfers;
    @Autowired CategoryService categories;
    @Autowired InsightService insights;
    @Autowired IbanHasher hasher;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main, savings, card;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = account("Main", "RO08TEST0000000000000001");
        savings = account("Savings", "RO78TEST0000000000000002");
        card = account("Card", "RO51TEST0000000000000003");
    }

    long account(String name, String iban) {
        var parsed = Iban.parse(iban).orElseThrow();
        return jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, iban_hash, iban_masked, currency, kind)
                VALUES (1, 1, ?, ?, ?, 'RON', 'CURRENT') RETURNING id""", Long.class, name, hasher.hash(parsed),
                parsed.masked());
    }

    /** Rows as "date,description,amount". */
    void load(long account, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(",RON\r\n");
        }
        uploads.importOne("f" + account + "-" + csv.hashCode() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    void tie() throws Exception {
        load(main, "2026-03-02,ORDIN PLATA,-300.00");
        load(savings, "2026-03-03,INCASARE ORDIN PLATA,300.00");
        load(card, "2026-03-03,ALIMENTARE CARD,300.00");
    }

    long tx(long account, String date) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE account_id = ? AND booking_date = ?::date LIMIT 1",
                Long.class, account, date);
    }

    String state(long id) {
        return jdbc.queryForObject("SELECT transfer_state FROM transaction WHERE id = ?", String.class, id);
    }

    List<ReviewCard> tieCards() {
        return http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == Kind.TRANSFER_TIE).toList();
    }

    HttpStatus answer(long transactionId, Long pairWith) {
        var body = new HashMap<String, Object>();
        body.put("pairWith", pairWith);
        return HttpStatus.valueOf(http.postForEntity("/api/review/transfers/" + transactionId, body, String.class)
                .getStatusCode().value());
    }

    long spentInMarch() {
        return insights.month(YearMonth.of(2026, 3), "RON").spentMinor();
    }

    @Test
    void aTieIsOneQuestionListingTheTransfersItCouldBe() throws Exception {
        tie();

        var cards = tieCards();
        assertThat(cards).hasSize(1);
        var card = cards.getFirst();
        long out = tx(main, "2026-03-02");
        assertThat(card.key()).isEqualTo("transfer:" + out);
        assertThat(card.affectedMinor()).isEqualTo(30_000);
        assertThat(card.currency()).isEqualTo("RON");
        var tie = card.transferTie();
        assertThat(tie.transactionId()).isEqualTo(out);
        assertThat(tie.accountName()).isEqualTo("Main");
        assertThat(tie.date()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(tie.amountMinor()).isEqualTo(-30_000);
        assertThat(tie.options()).extracting(ReviewCard.TransferOption::transactionId)
                .containsExactly(tx(savings, "2026-03-03"), tx(this.card, "2026-03-03"));
        assertThat(tie.options()).extracting(ReviewCard.TransferOption::accountName).containsExactly("Savings", "Card");
        // Until answered it is ordinary money out.
        assertThat(state(out)).isNull();
        assertThat(spentInMarch()).isEqualTo(30_000);
    }

    @Test
    void pickingOnePairsThemForGood() throws Exception {
        tie();
        long out = tx(main, "2026-03-02");
        long toSavings = tx(savings, "2026-03-03");

        assertThat(answer(out, toSavings)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(state(out)).isEqualTo("PAIRED");
        assertThat(state(toSavings)).isEqualTo("PAIRED");
        assertThat(state(tx(card, "2026-03-03"))).isNull();
        assertThat(jdbc.queryForMap("SELECT out_transaction_id, in_transaction_id, source FROM transfer_pair"))
                .containsEntry("out_transaction_id", out).containsEntry("in_transaction_id", toSavings)
                .containsEntry("source", "USER");
        assertThat(jdbc.queryForObject("SELECT c.code FROM transaction t JOIN category c ON c.id = t.category_id WHERE t.id = ?",
                String.class, out)).isEqualTo("TRANSFER");
        assertThat(spentInMarch()).isZero();
        assertThat(tieCards()).isEmpty();

        // Later statements and reruns keep the answer.
        load(card, "2026-03-20,ALIMENTARE CARD,50.00");
        assertThat(transfers.pairAll()).isEqualTo(new TransferService.Result(0, 0));
        assertThat(state(out)).isEqualTo("PAIRED");
        assertThat(jdbc.queryForObject("SELECT source FROM transfer_pair", String.class)).isEqualTo("USER");
    }

    @Test
    void noneOfTheseLeavesItOrdinaryAndDoesNotAskAgain() throws Exception {
        tie();
        long out = tx(main, "2026-03-02");

        assertThat(answer(out, null)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(tieCards()).isEmpty();
        assertThat(transfers.counts()).isEmpty();
        assertThat(spentInMarch()).isEqualTo(30_000);
        load(card, "2026-03-20,ALIMENTARE CARD,50.00");
        assertThat(tieCards()).isEmpty();
        assertThat(transfers.counts()).isEmpty();
    }

    @Test
    void answeringOneSettlesTheRestOfTheGroup() throws Exception {
        // Two equal transfers the same day, to Savings and to Card: four equally good pairs, one question.
        load(main, "2026-03-02,ORDIN PLATA A,-500.00", "2026-03-02,ORDIN PLATA B,-500.00");
        load(savings, "2026-03-03,INCASARE ORDIN PLATA,500.00");
        load(card, "2026-03-03,ALIMENTARE CARD,500.00");
        var cards = tieCards();
        assertThat(cards).hasSize(1);
        long first = cards.getFirst().transferTie().transactionId();
        assertThat(cards.getFirst().transferTie().options()).hasSize(2);

        assertThat(answer(first, tx(savings, "2026-03-03"))).isEqualTo(HttpStatus.NO_CONTENT);

        // The other money out now has one partner left: paired automatically.
        assertThat(transfers.counts()).isEqualTo(Map.of("PAIRED", 4L));
        assertThat(jdbc.queryForList("SELECT source FROM transfer_pair ORDER BY source", String.class))
                .containsExactly("AUTO", "USER");
        assertThat(spentInMarch()).isZero();
        assertThat(tieCards()).isEmpty();
    }

    @Test
    void aLaterNonTransferCategoryStillWins() throws Exception {
        tie();
        long out = tx(main, "2026-03-02");
        answer(out, tx(savings, "2026-03-03"));

        categories.setCategory(out, categories.categoryId("SHOPPING"), false);
        uploads.derive();

        assertThat(state(out)).isNull();
        assertThat(transfers.counts()).isEmpty();
        assertThat(spentInMarch()).isEqualTo(30_000);
    }

    @Test
    void onlyAnOpenQuestionAndAnOfferedTransferAreAccepted() throws Exception {
        tie();
        long out = tx(main, "2026-03-02");

        assertThat(answer(tx(savings, "2026-03-03"), out)).isEqualTo(HttpStatus.NOT_FOUND); // asked through Main
        assertThat(answer(999_999, null)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(answer(out, out)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfers.counts()).isEmpty();

        // Skippable like every card: back after the next upload.
        assertThat(http.postForEntity("/api/review/skip", Map.of("key", "transfer:" + out), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tieCards()).isEmpty();
        load(card, "2026-03-20,ALIMENTARE CARD,50.00");
        assertThat(tieCards()).hasSize(1);

        answer(out, tx(card, "2026-03-03"));
        assertThat(answer(out, null)).isEqualTo(HttpStatus.NOT_FOUND); // answered
    }
}
