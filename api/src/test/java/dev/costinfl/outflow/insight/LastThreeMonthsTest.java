package dev.costinfl.outflow.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.txn.TransactionController.TransactionList;
import java.nio.charset.StandardCharsets;
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
 * CP6.3 on the hand-computed ledger (LedgerFixture): Lidl 1,000 / 1,200 / 1,100 RON in Dec–Feb; March spent 1,300
 * (groceries 450). March alone: groceries are down 59% vs. a usual 1,100, the insight line. Jan–Mar: 3,600 spent,
 * 1,200 a month vs. December's 1,000 (+20%); groceries 2,750 (916.67 a month vs. 1,000: −8%, not notable).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LastThreeMonthsTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "Main");
        uploads.importOne("ledger.csv", LedgerFixture.LEDGER.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    MonthSummary summary(String query) {
        var r = http.getForEntity("/api/insights/month?month=2026-03" + query, MonthSummary.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    TransactionList list(String query) {
        return http.getForObject("/api/transactions?month=2026-03" + query, TransactionList.class);
    }

    @Test
    void oneMonthHasTheGroceriesInsight() {
        var s = summary("");

        assertThat(s.months()).isEqualTo(1);
        assertThat(s.periodFrom()).isEqualTo("2026-03");
        assertThat(s.monthsWithData()).isEqualTo(1);
        var i = s.insight();
        assertThat(i.code()).isEqualTo("GROCERIES");
        assertThat(i.deltaPct()).isEqualTo(-59);
        assertThat(i.differenceMinor()).isEqualTo(-65_000);
        assertThat(i.perMonthMinor()).isEqualTo(45_000);
        assertThat(i.usualMinor()).isEqualTo(110_000);
        assertThat(i.transactionCount()).isEqualTo(3); // 300, 200 and the 50 refund
    }

    @Test
    void threeMonthsAreTotalsComparedPerMonth() {
        var s = summary("&months=3");

        assertThat(s.months()).isEqualTo(3);
        assertThat(s.periodFrom()).isEqualTo("2026-01");
        assertThat(s.monthsWithData()).isEqualTo(3);
        assertThat(s.spentMinor()).isEqualTo(360_000);
        assertThat(s.incomeMinor()).isEqualTo(500_000);
        assertThat(s.baselineMonths()).isEqualTo(1); // only December precedes January
        assertThat(s.averageSpentMinor()).isEqualTo(100_000);
        assertThat(s.deltaPct()).isEqualTo(20);
        var groceries = s.categories().getFirst();
        assertThat(groceries.code()).isEqualTo("GROCERIES");
        assertThat(groceries.spentMinor()).isEqualTo(275_000);
        assertThat(groceries.usualMinor()).isEqualTo(100_000);
        assertThat(groceries.deltaPct()).isEqualTo(-8);
        assertThat(groceries.transactionCount()).isEqualTo(5);
        assertThat(s.insight()).isNull();
    }

    @Test
    void threeMonthFiguresEqualTheirThreeMonthDrillThrough() {
        var s = summary("&months=3");

        var spend = list("&months=3&scope=SPEND");
        assertThat(spend.months()).isEqualTo(3);
        assertThat(spend.totalMinor()).isEqualTo(s.spentMinor());
        assertThat(list("&months=3&scope=INCOME").totalMinor()).isEqualTo(s.incomeMinor());
        for (var c : s.categories()) {
            String category = c.categoryId() == null ? "&uncategorized=true" : "&category=" + c.categoryId();
            var drill = list("&months=3&scope=SPEND" + category);
            assertThat(drill.totalMinor()).as(c.name()).isEqualTo(c.spentMinor());
            assertThat(drill.count()).as(c.name()).isEqualTo(c.transactionCount());
        }
    }

    @Test
    void onlyOneOrThreeMonths() {
        assertThat(http.getForEntity("/api/insights/month?month=2026-03&months=2", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/transactions?month=2026-03&months=6", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
