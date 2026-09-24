package dev.costinfl.outflow.txn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.CategoryDetail;
import dev.costinfl.outflow.insight.CategoryDetail.MonthAmount;
import dev.costinfl.outflow.insight.LedgerFixture;
import dev.costinfl.outflow.insight.MonthSummary;
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

/** CP3.3: the transactions and category-detail endpoints, against the hand-computed ledger, over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TransactionControllerTest {

    @Autowired TestRestTemplate http;
    @Autowired UploadService uploads;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "Main");
        uploads.importOne("ledger.csv", LedgerFixture.LEDGER.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    TransactionList list(String query) {
        var r = http.getForEntity("/api/transactions?month=2026-03" + query, TransactionList.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    long categoryId(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }

    @Test
    void scopesMatchTheHomeScreen() {
        var home = http.getForObject("/api/insights/month?month=2026-03", MonthSummary.class);

        var spend = list("&scope=SPEND");
        assertThat(spend.totalMinor()).isEqualTo(home.spentMinor()).isEqualTo(130_000);
        assertThat(spend.count()).isEqualTo(10);
        assertThat(list("&scope=INCOME").totalMinor()).isEqualTo(home.incomeMinor()).isEqualTo(500_000);
        // everything: 5,000 + 30 in, 1,300 spent, 1,000 to savings
        var all = list("");
        assertThat(all.count()).isEqualTo(13);
        assertThat(all.totalMinor()).isEqualTo(273_000);
        assertThat(all.items()).extracting(TransactionView::bookingDate).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void everyHomeCategoryRowDrillsToItsTransactions() {
        var home = http.getForObject("/api/insights/month?month=2026-03", MonthSummary.class);
        for (var c : home.categories()) {
            var rows = c.categoryId() == null ? list("&scope=SPEND&uncategorized=true") : list("&scope=SPEND&category=" + c.categoryId());
            assertThat(rows.totalMinor()).as(c.name()).isEqualTo(c.spentMinor());
            assertThat(rows.count()).isEqualTo(c.transactionCount());
        }
        assertThat(list("&scope=SPEND&uncategorized=true").totalMinor()).isEqualTo(home.uncategorizedMinor());
    }

    @Test
    void searchByMerchantTextOrExactAmount() {
        assertThat(list("&q=starbucks").items()).singleElement().extracting(TransactionView::merchantName).isEqualTo("Starbucks");
        assertThat(list("&q=210.01").items()).singleElement().extracting(TransactionView::amountMinor).isEqualTo(-21_001L);
        assertThat(list("&q=210,01").count()).isEqualTo(1);
        assertThat(list("&q=5.000,00").items()).singleElement().extracting(TransactionView::merchantKey).isEqualTo("SALARIU ACME SRL");
        assertThat(list("&q=100_%25").count()).as("LIKE wildcards are literal").isZero();
    }

    @Test
    void merchantFilterAndValidation() {
        long lidl = jdbc.queryForObject("SELECT id FROM merchant WHERE key = 'LIDL'", Long.class);
        var rows = list("&merchant=" + lidl);
        assertThat(rows.count()).isEqualTo(3);
        assertThat(rows.totalMinor()).isEqualTo(-45_000); // ALL scope: net, so money out is negative

        assertThat(http.getForEntity("/api/transactions?month=2026-3", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/transactions", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void categoryDetailTrendAverageAndMerchants() {
        var d = http.getForObject("/api/insights/categories/" + categoryId("GROCERIES") + "?month=2026-03", CategoryDetail.class);

        assertThat(d.category().code()).isEqualTo("GROCERIES");
        assertThat(d.amountMinor()).isEqualTo(45_000);
        assertThat(d.trend()).hasSize(12);
        assertThat(d.trend().getFirst().month()).isEqualTo("2025-04");
        assertThat(d.trend().subList(8, 12)).extracting(MonthAmount::month, MonthAmount::amountMinor).containsExactly(
                org.assertj.core.groups.Tuple.tuple("2025-12", 100_000L), org.assertj.core.groups.Tuple.tuple("2026-01", 120_000L),
                org.assertj.core.groups.Tuple.tuple("2026-02", 110_000L), org.assertj.core.groups.Tuple.tuple("2026-03", 45_000L));
        assertThat(d.trend().subList(0, 8)).allSatisfy(m -> assertThat(m.hasData()).isFalse());
        assertThat(d.averageMinor()).isEqualTo(93_750); // (1,000 + 1,200 + 1,100 + 450) / 4 months with data
        assertThat(d.merchants()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Lidl");
            assertThat(m.amountMinor()).isEqualTo(45_000);
            assertThat(m.transactionCount()).isEqualTo(3);
        });
        // drills: the category's month equals its transactions
        assertThat(list("&scope=SPEND&category=" + categoryId("GROCERIES")).totalMinor()).isEqualTo(d.amountMinor());
    }

    @Test
    void incomeCategoriesCountMoneyIn() {
        var d = http.getForObject("/api/insights/categories/" + categoryId("INCOME") + "?month=2026-03", CategoryDetail.class);

        assertThat(d.amountMinor()).isEqualTo(500_000);
        assertThat(http.getForEntity("/api/insights/categories/4242?month=2026-03", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
