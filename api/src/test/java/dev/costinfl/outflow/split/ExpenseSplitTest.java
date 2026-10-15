package dev.costinfl.outflow.split;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.CategoryDetail;
import dev.costinfl.outflow.insight.MonthSummary;
import dev.costinfl.outflow.split.SplitController.ExpenseSplit;
import dev.costinfl.outflow.split.SplitController.ExpenseSplitList;
import dev.costinfl.outflow.txn.TransactionController.TransactionList;
import dev.costinfl.outflow.txn.TransactionView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.21: "this should be split". In March 2026 the user pays 300.00 at Lidl for a shared shopping trip with Ana and
 * Dan, and 100.00 at Kaufland alone; the salary comes in. Split equally, the Lidl payment counts 100.00 everywhere,
 * and every figure still equals its drill-through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ExpenseSplitTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main, lidl, salary;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-03-02,CUMPARARE POS LIDL,-300.00,RON\r\n"
                + "2026-03-05,CUMPARARE POS KAUFLAND,-100.00,RON\r\n"
                + "2026-03-10,INCASARE SALARIU ACME SRL,5000.00,RON\r\n";
        uploads.importOne("march.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());
        lidl = id("-30000");
        salary = id("500000");
    }

    long id(String amountMinor) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE amount_minor = ?::bigint", Long.class, amountMinor);
    }

    MonthSummary month() {
        return http.getForObject("/api/insights/month?month=2026-03", MonthSummary.class);
    }

    TransactionList spending(String query) {
        return http.getForObject("/api/transactions?month=2026-03&scope=SPEND" + query, TransactionList.class);
    }

    org.springframework.http.ResponseEntity<String> put(long transactionId, Object body) {
        return http.exchange("/api/transactions/" + transactionId + "/split", HttpMethod.PUT, new HttpEntity<>(body),
                String.class);
    }

    static Map<String, Object> split(String mode, List<Map<String, Object>> people) {
        return Map.of("mode", mode, "people", people);
    }

    @Test
    void anEqualSplitCountsOnlyTheUsersShareEverywhere() {
        assertThat(month().spentMinor()).isEqualTo(40_000);

        var r = put(lidl, split("EQUAL", List.of(Map.of("name", "Ana"), Map.of("name", "Dan"))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        var m = month();
        assertThat(m.spentMinor()).isEqualTo(20_000); // 100.00 of Lidl + 100.00 of Kaufland
        assertThat(spending("").totalMinor()).isEqualTo(m.spentMinor());
        var groceries = m.categories().getFirst();
        assertThat(groceries.spentMinor()).isEqualTo(20_000);
        assertThat(spending("&category=" + groceries.categoryId()).totalMinor()).isEqualTo(groceries.spentMinor());
        var detail = http.getForObject("/api/insights/categories/" + groceries.categoryId() + "?month=2026-03",
                CategoryDetail.class);
        assertThat(detail.amountMinor()).isEqualTo(20_000);
        assertThat(detail.merchants()).extracting(CategoryDetail.MerchantAmount::amountMinor)
                .containsExactly(10_000L, 10_000L);
        assertThat(m.netMinor()).isEqualTo(500_000 - 20_000);

        // The row keeps the bank's amount and says what the others owe.
        TransactionView row = spending("").items().stream().filter(t -> t.id() == lidl).findFirst().orElseThrow();
        assertThat(row.amountMinor()).isEqualTo(-30_000);
        assertThat(row.sharedMinor()).isEqualTo(20_000);

        var listed = http.getForObject("/api/splits", ExpenseSplitList.class);
        assertThat(listed.splits()).singleElement().satisfies(s -> {
            assertThat(s.transactionId()).isEqualTo(lidl);
            assertThat(s.amountMinor()).isEqualTo(30_000);
            assertThat(s.mineMinor()).isEqualTo(10_000);
            assertThat(s.othersMinor()).isEqualTo(20_000);
            assertThat(s.people()).extracting(SplitController.ExpenseSplitPerson::name).containsExactly("Ana", "Dan");
        });
        assertThat(listed.people()).containsExactly("Ana", "Dan");
    }

    @Test
    void aSplitCanBeChangedAndUndone() {
        put(lidl, split("EQUAL", List.of(Map.of("name", "Ana"), Map.of("name", "Dan"))));

        var r = put(lidl, split("FIXED", List.of(Map.of("name", "Ana", "fixedMinor", 5_000))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        var split = http.getForObject("/api/transactions/" + lidl + "/split", ExpenseSplit.class);
        assertThat(split.mode()).isEqualTo(SplitCalculator.Mode.FIXED);
        assertThat(split.people()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("Ana");
            assertThat(p.fixedMinor()).isEqualTo(5_000);
            assertThat(p.amountMinor()).isEqualTo(5_000);
        });
        assertThat(month().spentMinor()).isEqualTo(35_000);

        var gone = http.exchange("/api/transactions/" + lidl + "/split", HttpMethod.DELETE, null, String.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(month().spentMinor()).isEqualTo(40_000);
        assertThat(http.exchange("/api/transactions/" + lidl + "/split", HttpMethod.DELETE, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/transactions/" + lidl + "/split", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSplitMustAddUpAndOnlyPostedMoneyOutSplits() throws Exception {
        var overHundred = put(lidl, split("PERCENT", List.of(Map.of("name", "Ana", "percent", 60),
                Map.of("name", "Dan", "percent", 50))));
        assertThat(overHundred.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(lidl, split("EQUAL", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(lidl, Map.of("people", List.of(Map.of("name", "Ana")))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(salary, split("EQUAL", List.of(Map.of("name", "Ana")))).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(999_999, split("EQUAL", List.of(Map.of("name", "Ana")))).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense_split", Long.class)).isZero();
        assertThat(month().spentMinor()).isEqualTo(40_000);

        // A pending card payment may still change: split it once it is posted.
        uploads.importOne("march.xml", Files.readAllBytes(Path.of("..", "samples", "synthetic", "camt053-2026-03.xml")),
                Optional.empty(), Optional.empty());
        long pending = jdbc.queryForObject("SELECT id FROM transaction WHERE status = 'PENDING'", Long.class);
        var r = put(pending, split("EQUAL", List.of(Map.of("name", "Ana"))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void sharesAndTheUsersOwnWeight() {
        var r = put(lidl, Map.of("mode", "SHARES", "myShares", 0, "people",
                List.of(Map.of("name", "Ana", "shares", 2), Map.of("name", "Dan", "shares", 1))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        var split = http.getForObject("/api/transactions/" + lidl + "/split", ExpenseSplit.class);
        assertThat(split.myShares()).isZero();
        assertThat(split.people()).extracting(SplitController.ExpenseSplitPerson::amountMinor).containsExactly(20_000L, 10_000L);
        assertThat(split.mineMinor()).isZero();
        assertThat(month().spentMinor()).isEqualTo(10_000); // only Kaufland is the user's
        assertThat(spending("").totalMinor()).isEqualTo(10_000);
    }
}
