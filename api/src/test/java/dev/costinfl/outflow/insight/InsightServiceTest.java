package dev.costinfl.outflow.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.MonthSummary.CategorySpend;
import dev.costinfl.outflow.txn.TransactionFilter;
import dev.costinfl.outflow.txn.TransactionQueries;
import dev.costinfl.outflow.txn.TransactionView;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP3.1: home-screen numbers against a hand-computed month, and the drill-through invariant on every sample month. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class InsightServiceTest {

    static final YearMonth MARCH = YearMonth.of(2026, 3);

    /**
     * Baseline: Lidl 1,000 / 1,200 / 1,100 RON in Dec–Feb (average 1,100). March, computed by hand:
     * spent = groceries 450 (300 + 200 − 50 refund) + fuel 250 + utilities 210.01 + shopping 120 + restaurants 100
     * + transport 80 + subscriptions 49.99 + uncategorized 40 = 1,300.00. The 1,000 savings transfer, 5,000 salary
     * and an unidentified +30 inflow are not spending.
     */
    static final String LEDGER = """
            Date,Description,Amount,Currency
            2025-12-05,CUMPARARE POS LIDL,-1000.00,RON
            2026-01-05,CUMPARARE POS LIDL,-1200.00,RON
            2026-02-05,CUMPARARE POS LIDL,-1100.00,RON
            2026-03-02,CUMPARARE POS LIDL,-300.00,RON
            2026-03-09,CUMPARARE POS LIDL,-200.00,RON
            2026-03-10,CUMPARARE POS LIDL,50.00,RON
            2026-03-03,CUMPARARE POS STARBUCKS,-100.00,RON
            2026-03-04,BOLT.EU/R/1,-80.00,RON
            2026-03-05,CUMPARARE POS OMV,-250.00,RON
            2026-03-06,NETFLIX.COM,-49.99,RON
            2026-03-07,PLATA CARD EMAG.RO,-120.00,RON
            2026-03-08,ENEL ENERGIE FACTURA 1,-210.01,RON
            2026-03-11,CUMPARARE POS ZZ WIDGETS,-40.00,RON
            2026-03-10,INCASARE SALARIU ACME SRL,5000.00,RON
            2026-03-12,TRANSFER CATRE CONT ECONOMII,-1000.00,RON
            2026-03-13,INCASARE ZZ INFLOW,30.00,RON
            """;

    @Autowired UploadService uploads;
    @Autowired InsightService insights;
    @Autowired TransactionQueries transactions;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long account;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        account = ImportFixtures.newAccount(jdbc, "Main");
    }

    void load(String csv) throws Exception {
        uploads.importOne("ledger.csv", csv.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    static long spentOf(List<TransactionView> rows) {
        return rows.stream().mapToLong(t -> -t.amountMinor()).sum();
    }

    @Nested
    class HandComputedMonth {

        @BeforeEach
        void loadLedger() throws Exception {
            load(LEDGER);
        }

        @Test
        void block1SpentAverageIncomeNet() {
            var s = insights.month(MARCH, "RON");

            assertThat(s.spentMinor()).isEqualTo(130_000);
            assertThat(s.baselineMonths()).isEqualTo(3);
            assertThat(s.averageSpentMinor()).isEqualTo(110_000);
            assertThat(s.deltaPct()).isEqualTo(18); // +18.18%
            assertThat(s.incomeMinor()).isEqualTo(500_000);
            assertThat(s.netMinor()).isEqualTo(370_000);
            assertThat(s.availableMonths()).containsExactly("2025-12", "2026-01", "2026-02", "2026-03");
        }

        @Test
        void block2TopFiveWithUsualAndTheRestFolded() {
            var s = insights.month(MARCH, "RON");

            assertThat(s.categories()).extracting(CategorySpend::code, CategorySpend::spentMinor, CategorySpend::sharePct)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("GROCERIES", 45_000L, 35),
                            org.assertj.core.groups.Tuple.tuple("FUEL", 25_000L, 19),
                            org.assertj.core.groups.Tuple.tuple("UTILITIES", 21_001L, 16),
                            org.assertj.core.groups.Tuple.tuple("SHOPPING", 12_000L, 9),
                            org.assertj.core.groups.Tuple.tuple("RESTAURANTS", 10_000L, 8));
            var groceries = s.categories().getFirst();
            assertThat(groceries.usualMinor()).isEqualTo(110_000);
            assertThat(groceries.deltaPct()).isEqualTo(-59);
            assertThat(groceries.transactionCount()).isEqualTo(3);
            assertThat(s.categories().get(1).usualMinor()).isZero();
            assertThat(s.categories().get(1).deltaPct()).as("no usual to compare with").isNull();

            assertThat(s.rest().spentMinor()).isEqualTo(16_999); // transport 80 + subscriptions 49.99 + uncategorized 40
            assertThat(s.rest().categoryCount()).isEqualTo(3);
            assertThat(s.rest().sharePct()).isEqualTo(13);
            // uncategorized ranks 8th, folded into the rest, but is still reported on its own
            assertThat(s.uncategorizedMinor()).isEqualTo(4_000);
            assertThat(s.uncategorizedCount()).isEqualTo(1);
        }

        @Test
        void block4AccuracyWeighsSpendingByConfidence() {
            var s = insights.month(MARCH, "RON");

            // 1,360 of 1,400 RON of spend movements are categorized, all by keyword (0.70): 952 / 1,400 = 68%
            assertThat(s.categorizedPct()).isEqualTo(97);
            assertThat(s.accuracyPct()).isEqualTo(68);
        }

        @Test
        void everyNumberEqualsTheSumOfItsTransactions() {
            var s = insights.month(MARCH, "RON");
            var month = TransactionFilter.month(MARCH, "RON");

            assertThat(spentOf(transactions.list(month.spend()))).isEqualTo(s.spentMinor());
            assertThat(transactions.list(month.income()).stream().mapToLong(TransactionView::amountMinor).sum())
                    .isEqualTo(s.incomeMinor());
            for (CategorySpend c : s.categories()) {
                var rows = transactions.list(c.categoryId() == null ? month.spend().uncategorizedOnly()
                        : month.spend().inCategory(c.categoryId()));
                assertThat(spentOf(rows)).as(c.name()).isEqualTo(c.spentMinor());
                assertThat(rows).hasSize(c.transactionCount());
            }
            assertThat(spentOf(transactions.list(month.spend().uncategorizedOnly()))).isEqualTo(4_000);
        }

        @Test
        void transfersAndIncomeAreNeverSpending() {
            var spendRows = transactions.list(TransactionFilter.month(MARCH, "RON").spend());

            assertThat(spendRows).extracting(TransactionView::merchantKey)
                    .doesNotContain("CONT ECONOMII", "SALARIU ACME SRL", "ZZ INFLOW");
        }

        @Test
        void historyShorterThanThreeMonths() {
            var first = insights.month(YearMonth.of(2025, 12), "RON");
            assertThat(first.baselineMonths()).isZero();
            assertThat(first.averageSpentMinor()).isNull();
            assertThat(first.deltaPct()).isNull();

            var second = insights.month(YearMonth.of(2026, 1), "RON");
            assertThat(second.baselineMonths()).isEqualTo(1);
            assertThat(second.averageSpentMinor()).isEqualTo(100_000);
            assertThat(second.deltaPct()).isEqualTo(20);
        }

        @Test
        void aManualCategoryRaisesAccuracy() {
            long widgets = transactions.list(TransactionFilter.month(MARCH, "RON").spend().uncategorizedOnly()).getFirst().id();
            jdbc.update("UPDATE transaction SET category_id = 11, category_source = 'USER', category_confidence = 1 WHERE id = ?", widgets);

            var s = insights.month(MARCH, "RON");

            assertThat(s.categorizedPct()).isEqualTo(100);
            assertThat(s.uncategorizedMinor()).isZero();
            assertThat(s.uncategorizedCount()).isZero();
            assertThat(s.accuracyPct()).isEqualTo(71); // (952 + 40) / 1,400 = 70.86%
        }

        @Test
        void endpointDefaultsToTheLatestMonthAndValidates() {
            var s = http.getForObject("/api/insights/month", MonthSummary.class);
            assertThat(s.month()).isEqualTo("2026-03");
            assertThat(s.spentMinor()).isEqualTo(130_000);

            assertThat(http.getForEntity("/api/insights/month?month=2026-13", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(http.getForEntity("/api/insights/month?currency=ron", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void invariantsHoldForEveryMonthOfTheSamples() throws Exception {
        for (String file : new String[] {"generic-2026-01-to-03.csv", "generic-2026-02-to-04.csv"}) {
            uploads.importOne(file, ImportFixtures.sample(file), Optional.of(account), Optional.empty());
        }
        for (String m : insights.availableMonths("RON").stream().map(YearMonth::toString).toList()) {
            var s = insights.month(YearMonth.parse(m), "RON");
            var filter = TransactionFilter.month(YearMonth.parse(m), "RON");

            long top = s.categories().stream().mapToLong(CategorySpend::spentMinor).sum();
            assertThat(top + s.rest().spentMinor()).as(m + " top 5 + rest = spent").isEqualTo(s.spentMinor());
            assertThat(spentOf(transactions.list(filter.spend()))).as(m + " spent drills").isEqualTo(s.spentMinor());
            assertThat(s.netMinor()).isEqualTo(s.incomeMinor() - s.spentMinor());
            assertThat(s.incomeMinor()).as(m + " salary").isEqualTo(850_000);
        }
    }

    @Test
    void emptyDatabaseGivesZerosNotErrors() {
        var s = insights.month(MARCH, "RON");

        assertThat(s.spentMinor()).isZero();
        assertThat(s.categories()).isEmpty();
        assertThat(s.accuracyPct()).isZero();
        assertThat(s.availableMonths()).isEmpty();
    }
}
