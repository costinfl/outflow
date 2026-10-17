package dev.costinfl.outflow.split;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.MonthSummary;
import dev.costinfl.outflow.split.SettlementController.RepaymentSuggestion;
import dev.costinfl.outflow.split.SettlementController.SplitBalance;
import dev.costinfl.outflow.split.SettlementController.SplitBalances;
import dev.costinfl.outflow.split.SettlementController.SplitDebt;
import dev.costinfl.outflow.txn.TransactionController.TransactionList;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.22: "Splits to settle". March 2026: a 300.00 dinner split equally with Ana Ionescu and Dan (100.00 each), a
 * 90.00 shopping trip split with Ana (45.00). Ana sends 145.00 for both on the 12th, Dan 100.00 on the 14th, and
 * someone else 100.00 on the 15th.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SplitSettlementTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long dinner, shopping, fromAna, fromDan, fromOther;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long main = ImportFixtures.newAccount(jdbc, "Main");
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-03-02,CUMPARARE POS LA MAMA RESTAURANT,-300.00,RON\r\n"
                + "2026-03-06,CUMPARARE POS KAUFLAND,-90.00,RON\r\n"
                + "2026-03-10,INCASARE SALARIU ACME SRL,5000.00,RON\r\n"
                + "2026-03-12,INCASARE TRANSFER ANA IONESCU,145.00,RON\r\n"
                + "2026-03-14,INCASARE DAN POPA,100.00,RON\r\n"
                + "2026-03-15,INCASARE RAMBURSARE,100.00,RON\r\n";
        uploads.importOne("march.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());
        dinner = id("RESTAURANT");
        shopping = id("KAUFLAND");
        fromAna = id("ANA IONESCU");
        fromDan = id("DAN POPA");
        fromOther = id("RAMBURSARE");
        split(dinner, "Ana Ionescu", "Dan");
        split(shopping, "Ana Ionescu");
    }

    long id(String text) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE description_raw LIKE ?", Long.class, "%" + text + "%");
    }

    void split(long transactionId, String... people) {
        var body = Map.of("mode", "EQUAL", "people", java.util.Arrays.stream(people).map(n -> Map.of("name", n)).toList());
        var r = http.exchange("/api/transactions/" + transactionId + "/split", HttpMethod.PUT, new HttpEntity<>(body),
                String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    List<SplitBalance> balances() {
        return http.getForObject("/api/splits/balances", SplitBalances.class).people();
    }

    SplitBalance person(String name) {
        return balances().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    SplitDebt debt(String name, long expense) {
        return person(name).debts().stream().filter(d -> d.transactionId() == expense).findFirst().orElseThrow();
    }

    ResponseEntity<String> settle(long splitPersonId, Long transactionId, Long amountMinor) {
        var body = new HashMap<String, Object>();
        body.put("splitPersonId", splitPersonId);
        body.put("transactionId", transactionId);
        body.put("amountMinor", amountMinor);
        return http.postForEntity("/api/splits/settlements", body, String.class);
    }

    MonthSummary month() {
        return http.getForObject("/api/insights/month?month=2026-03", MonthSummary.class);
    }

    void categorize(long transactionId, String code) {
        long category = jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
        http.put("/api/transactions/" + transactionId + "/category", Map.of("categoryId", category));
    }

    @Test
    void eachPersonOwesTheirPartsTheLargestFirst() {
        var people = balances();
        assertThat(people).extracting(SplitBalance::name).containsExactly("Ana Ionescu", "Dan");
        assertThat(people.getFirst().openMinor()).isEqualTo(14_500);
        assertThat(people.getFirst().debts()).extracting(SplitDebt::transactionId).containsExactly(shopping, dinner);
        assertThat(people.get(1).openMinor()).isEqualTo(10_000);
    }

    @Test
    void suggestionsNameThePersonOrMatchTheAmount() {
        assertThat(debt("Dan", dinner).suggestions()).extracting(RepaymentSuggestion::transactionId)
                .containsExactly(fromDan, fromOther); // name and amount, then amount only
        assertThat(debt("Dan", dinner).suggestions().getFirst().namesPerson()).isTrue();
        assertThat(debt("Dan", dinner).suggestions().getFirst().sameAmount()).isTrue();
        // Ana's 145.00 names her, for either debt; the others only match the dinner's 100.00.
        assertThat(debt("Ana Ionescu", shopping).suggestions()).extracting(RepaymentSuggestion::transactionId)
                .containsExactly(fromAna);
        assertThat(debt("Ana Ionescu", dinner).suggestions()).extracting(RepaymentSuggestion::transactionId)
                .containsExactly(fromAna, fromDan, fromOther);
    }

    @Test
    void oneRepaymentCoversTwoSplitsAndIsNeitherIncomeNorARefund() {
        categorize(fromAna, "INCOME");
        categorize(fromDan, "RESTAURANTS"); // a refund of the dinner, as the bank would have it
        assertThat(month().incomeMinor()).isEqualTo(514_500);
        assertThat(month().spentMinor()).isEqualTo(10_000 + 4_500 - 10_000);

        assertThat(settle(debt("Ana Ionescu", dinner).splitPersonId(), fromAna, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(settle(debt("Ana Ionescu", shopping).splitPersonId(), fromAna, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(settle(debt("Dan", dinner).splitPersonId(), fromDan, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(person("Ana Ionescu").openMinor()).isZero();
        assertThat(debt("Ana Ionescu", dinner).repayments()).singleElement()
                .satisfies(r -> assertThat(r.amountMinor()).isEqualTo(10_000));
        assertThat(debt("Ana Ionescu", shopping).settledMinor()).isEqualTo(4_500);
        assertThat(jdbc.queryForObject("SELECT repaid_minor FROM transaction WHERE id = ?", Long.class, fromAna))
                .isEqualTo(14_500);

        var m = month();
        assertThat(m.incomeMinor()).isEqualTo(500_000); // the salary only
        assertThat(m.spentMinor()).isEqualTo(14_500); // the user's shares, no refund
        assertThat(http.getForObject("/api/transactions?month=2026-03&scope=INCOME", TransactionList.class).totalMinor())
                .isEqualTo(m.incomeMinor());
        assertThat(http.getForObject("/api/transactions?month=2026-03&scope=SPEND", TransactionList.class).totalMinor())
                .isEqualTo(m.spentMinor());

        assertThat(settle(debt("Ana Ionescu", dinner).splitPersonId(), fromOther, null).getStatusCode())
                .as("nothing left to pay").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void partlyOutsideTheBankAndUndo() {
        long dan = debt("Dan", dinner).splitPersonId();
        assertThat(settle(dan, null, 4_000L).getStatusCode()).isEqualTo(HttpStatus.CREATED); // cash
        assertThat(debt("Dan", dinner).openMinor()).isEqualTo(6_000);
        assertThat(settle(dan, fromDan, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(debt("Dan", dinner).openMinor()).isZero();
        assertThat(jdbc.queryForObject("SELECT repaid_minor FROM transaction WHERE id = ?", Long.class, fromDan))
                .as("the rest of Dan's 100.00 is ordinary money in").isEqualTo(6_000);

        long bank = debt("Dan", dinner).repayments().stream().filter(r -> r.transactionId() != null).findFirst()
                .orElseThrow().id();
        assertThat(http.exchange("/api/splits/settlements/" + bank, HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(debt("Dan", dinner).openMinor()).isEqualTo(6_000);
        assertThat(jdbc.queryForObject("SELECT repaid_minor FROM transaction WHERE id = ?", Long.class, fromDan)).isZero();
        assertThat(http.exchange("/api/splits/settlements/" + bank, HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRepaymentMustFitAndOnlyMoneyInPaysBack() {
        long dan = debt("Dan", dinner).splitPersonId();
        assertThat(settle(dan, null, 10_001L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(settle(dan, null, 0L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(settle(dan, shopping, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // money out
        assertThat(settle(dan, 999_999L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(settle(999_999, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM split_settlement", Long.class)).isZero();
    }

    @Test
    void changingASplitNeverLosesARepayment() {
        settle(debt("Ana Ionescu", dinner).splitPersonId(), fromAna, null); // 100.00 paid back

        var lower = Map.of("mode", "FIXED", "people", List.of(Map.of("name", "Ana Ionescu", "fixedMinor", 5_000),
                Map.of("name", "Dan", "fixedMinor", 10_000)));
        assertThat(put(dinner, lower).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var withoutAna = Map.of("mode", "EQUAL", "people", List.of(Map.of("name", "Dan")));
        assertThat(put(dinner, withoutAna).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange("/api/transactions/" + dinner + "/split", HttpMethod.DELETE, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A third person would lower Ana's part below what she paid back.
        var withIon = Map.of("mode", "EQUAL", "people", List.of(Map.of("name", "Ana Ionescu"), Map.of("name", "Dan"),
                Map.of("name", "Ion")));
        assertThat(put(dinner, withIon).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The same people in another spelling: Ana's repayment stays hers.
        var renamed = Map.of("mode", "PERCENT", "people", List.of(Map.of("name", "ana ionescu", "percent", 40),
                Map.of("name", "Dan", "percent", 30)));
        assertThat(put(dinner, renamed).getStatusCode()).isEqualTo(HttpStatus.OK);
        var ana = debt("Ana Ionescu", dinner); // grouped with her other split, under its newer spelling
        assertThat(ana.owedMinor()).isEqualTo(12_000);
        assertThat(ana.settledMinor()).isEqualTo(10_000);
        assertThat(ana.openMinor()).isEqualTo(2_000);
    }

    ResponseEntity<String> put(long transactionId, Object body) {
        return http.exchange("/api/transactions/" + transactionId + "/split", HttpMethod.PUT, new HttpEntity<>(body),
                String.class);
    }
}
