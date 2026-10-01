package dev.costinfl.outflow.insight;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.RecurringOverview;
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
 * CP6.17: more than one currency, one at a time (spec question 14; the user chose a switch over conversion). Main is a
 * RON account with four transactions in March 2026, Travel a EUR card with two. Nothing is ever converted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CurrencySwitchTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main, travel;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        main = ImportFixtures.newAccount(jdbc, "Main");
        travel = ImportFixtures.newAccount(jdbc, "Travel");
        load(main, "RON", "2026-03-02,CUMPARARE POS LIDL,-200.00", "2026-03-05,CUMPARARE POS KAUFLAND,-100.00",
                "2026-03-09,CUMPARARE POS LIDL,-50.00", "2026-03-10,INCASARE SALARIU ACME SRL,5000.00");
        load(travel, "EUR", "2026-03-14,CUMPARARE POS CARREFOUR,-40.00", "2026-03-15,CUMPARARE POS CARREFOUR,-25.50");
    }

    void load(long account, String currency, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(',').append(currency).append("\r\n");
        }
        uploads.importOne("f" + account + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8), Optional.of(account),
                Optional.empty());
    }

    MonthSummary month(String query) {
        return http.getForObject("/api/insights/month?month=2026-03" + query, MonthSummary.class);
    }

    TransactionList spending(String query) {
        return http.getForObject("/api/transactions?month=2026-03&scope=SPEND" + query, TransactionList.class);
    }

    @Test
    void withoutAChoiceTheMainCurrencyIsShownAndTheOthersAreListed() {
        var m = month("");

        assertThat(m.currency()).isEqualTo("RON"); // four transactions against two
        assertThat(m.currencies()).containsExactly("RON", "EUR");
        assertThat(m.spentMinor()).isEqualTo(35_000);
        assertThat(spending("").currency()).isEqualTo("RON");
        assertThat(spending("").totalMinor()).isEqualTo(m.spentMinor());
    }

    @Test
    void euroFiguresAreEuroTransactionsAndEqualTheirDrillThrough() {
        var m = month("&currency=EUR");

        assertThat(m.currency()).isEqualTo("EUR");
        assertThat(m.spentMinor()).isEqualTo(6_550);
        assertThat(m.currencies()).containsExactly("RON", "EUR");
        var rows = spending("&currency=EUR");
        assertThat(rows.totalMinor()).isEqualTo(m.spentMinor());
        assertThat(rows.items()).allSatisfy(t -> assertThat(t.currency()).isEqualTo("EUR"));
        long category = m.categories().getFirst().categoryId();
        var detail = http.getForObject("/api/insights/categories/" + category + "?month=2026-03&currency=EUR",
                CategoryDetail.class);
        assertThat(detail.currency()).isEqualTo("EUR");
        assertThat(detail.amountMinor()).isEqualTo(6_550);
        assertThat(http.getForObject("/api/subscriptions?currency=EUR", RecurringOverview.class)).isNotNull();
    }

    @Test
    void theAccountsFilterChoosesItsOwnMainCurrency() {
        var m = month("&accounts=" + travel);

        assertThat(m.currency()).isEqualTo("EUR");
        assertThat(m.currencies()).containsExactly("EUR");
        assertThat(m.spentMinor()).isEqualTo(6_550);
        assertThat(spending("&accounts=" + travel).totalMinor()).isEqualTo(6_550);
    }

    @Test
    void anUnknownCurrencyIsABadRequestAndNoDataFallsBackToRon() {
        assertThat(http.getForEntity("/api/insights/month?currency=XYZ", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/transactions?month=2026-03&currency=eur", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ImportFixtures.reset(jdbc);
        var empty = http.getForObject("/api/insights/month", MonthSummary.class);
        assertThat(empty.currency()).isEqualTo("RON");
        assertThat(empty.currencies()).isEmpty();
    }
}
