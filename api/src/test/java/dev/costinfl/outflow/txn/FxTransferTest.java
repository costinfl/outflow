package dev.costinfl.outflow.txn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.ingest.account.IbanHasher;
import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.insight.InsightService;
import dev.costinfl.outflow.review.ReviewCard;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import dev.costinfl.outflow.txn.FxRateController.FxPair;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.19: transfers between own accounts in two currencies (DESIGN: "within FX tolerance for cross-currency", spec
 * question 21). Main is a RON account, Travel a EUR one. On Monday 2 March 2026 500.00 RON leaves Main; on Tuesday
 * 100.50 EUR arrives in Travel (an implied rate of 4.975). Nothing is fetched: the rate is the user's. Synthetic IBANs
 * (valid checksums, fake bank code TEST).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FxTransferTest {

    static final String TRAVEL_IBAN = "RO78TEST0000000000000002";

    @Autowired UploadService uploads;
    @Autowired InsightService insights;
    @Autowired TransactionQueries transactions;
    @Autowired IbanHasher hasher;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long main, travel;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        main = account("Main", "RO08TEST0000000000000001", "RON");
        travel = account("Travel", TRAVEL_IBAN, "EUR");
    }

    long account(String name, String iban, String currency) {
        var parsed = Iban.parse(iban).orElseThrow();
        return jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, iban_hash, iban_masked, currency, kind)
                VALUES (1, 1, ?, ?, ?, ?, 'CURRENT') RETURNING id""", Long.class, name, hasher.hash(parsed),
                parsed.masked(), currency);
    }

    /** Rows as "date,description,amount" in the given currency. */
    void load(long account, String currency, String... rows) throws Exception {
        var csv = new StringBuilder("Date,Description,Amount,Currency\r\n");
        for (String r : rows) {
            csv.append(r).append(',').append(currency).append("\r\n");
        }
        uploads.importOne("f" + account + "-" + csv.hashCode() + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8),
                Optional.of(account), Optional.empty());
    }

    void exchange(String mainText) throws Exception {
        load(main, "RON", "2026-03-02," + mainText + ",-500.00");
        load(travel, "EUR", "2026-03-03,INCASARE TRANSFER,100.50");
    }

    long tx(long account) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE account_id = ? ORDER BY id LIMIT 1", Long.class,
                account);
    }

    String state(long id) {
        return jdbc.queryForObject("SELECT transfer_state FROM transaction WHERE id = ?", String.class, id);
    }

    String method() {
        return jdbc.queryForList("SELECT method FROM transfer_pair", String.class).stream().findFirst().orElse(null);
    }

    long spent(String currency) {
        return insights.month(YearMonth.of(2026, 3), currency).spentMinor();
    }

    HttpStatus setRate(String base, String quote, String rate, String tolerance) {
        var body = new java.util.HashMap<String, Object>();
        body.put("rate", new BigDecimal(rate));
        if (tolerance != null) {
            body.put("tolerancePercent", new BigDecimal(tolerance));
        }
        return HttpStatus.valueOf(http.exchange("/api/fx-rates/" + base + "/" + quote, HttpMethod.PUT,
                new HttpEntity<>(body), String.class).getStatusCode().value());
    }

    List<FxPair> rates() {
        return http.exchange("/api/fx-rates", HttpMethod.GET, null, new ParameterizedTypeReference<List<FxPair>>() {})
                .getBody();
    }

    @Test
    void withoutARateOrAnIbanTwoCurrenciesNeverPair() throws Exception {
        exchange("ORDIN PLATA");

        assertThat(state(tx(main))).isNull();
        assertThat(state(tx(travel))).isNull();
        assertThat(spent("RON")).isEqualTo(50_000);
        assertThat(rates()).singleElement().satisfies(p -> {
            assertThat(p.base()).isEqualTo("EUR");
            assertThat(p.quote()).isEqualTo("RON");
            assertThat(p.rate()).isNull();
            assertThat(p.lastSeenRate()).isNull();
        });
    }

    @Test
    void anIbanNamingTheOtherAccountPairsWithoutARate() throws Exception {
        exchange("ORDIN PLATA " + TRAVEL_IBAN);

        assertThat(state(tx(main))).isEqualTo("PAIRED");
        assertThat(state(tx(travel))).isEqualTo("PAIRED");
        assertThat(method()).isEqualTo("IBAN");
        assertThat(spent("RON")).isZero();
        assertThat(insights.month(YearMonth.of(2026, 3), "EUR").incomeMinor()).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT c.code FROM transaction t JOIN category c ON c.id = t.category_id",
                String.class)).containsExactly("TRANSFER");
        // Each side shows the other one, in its own currency.
        var out = transactions.find(tx(main)).orElseThrow();
        assertThat(out.transferAccountName()).isEqualTo("Travel");
        assertThat(out.transferAmountMinor()).isEqualTo(10_050);
        assertThat(out.transferCurrency()).isEqualTo("EUR");
        assertThat(transactions.find(tx(travel)).orElseThrow().transferAmountMinor()).isEqualTo(-50_000);
        // The rate the transfer implies is offered as a hint for the user's own.
        assertThat(rates()).singleElement().satisfies(p -> {
            assertThat(p.lastSeenRate()).isEqualByComparingTo("4.9751");
            assertThat(p.lastSeenOn()).isEqualTo(LocalDate.of(2026, 3, 3));
        });
    }

    @Test
    void theUsersRateAndToleranceDecideWhenNoIbanDoes() throws Exception {
        exchange("ORDIN PLATA");

        assertThat(setRate("EUR", "RON", "4.97", null)).isEqualTo(HttpStatus.OK); // 4.975 is 0.1% away
        assertThat(state(tx(main))).isEqualTo("PAIRED");
        assertThat(method()).isEqualTo("FX_RATE");
        assertThat(spent("RON")).isZero();
        assertThat(rates()).singleElement().satisfies(p -> {
            assertThat(p.rate()).isEqualByComparingTo("4.97");
            assertThat(p.tolerancePercent()).isEqualByComparingTo("3");
        });

        assertThat(setRate("EUR", "RON", "5.30", "3")).isEqualTo(HttpStatus.OK); // 6.1% away: not the same money
        assertThat(state(tx(main))).isNull();
        assertThat(spent("RON")).isEqualTo(50_000);

        assertThat(setRate("EUR", "RON", "5.30", "7")).isEqualTo(HttpStatus.OK); // a wider tolerance takes it in
        assertThat(state(tx(main))).isEqualTo("PAIRED");

        assertThat(http.exchange("/api/fx-rates/EUR/RON", HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(state(tx(main))).isNull();
        assertThat(spent("RON")).isEqualTo(50_000);
        assertThat(http.exchange("/api/fx-rates/EUR/RON", HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRateGivenTheOtherWayRoundIsStoredInverted() {
        assertThat(setRate("RON", "EUR", "0.2", "2.5")).isEqualTo(HttpStatus.OK);

        assertThat(rates()).singleElement().satisfies(p -> {
            assertThat(p.base()).isEqualTo("EUR");
            assertThat(p.quote()).isEqualTo("RON");
            assertThat(p.rate()).isEqualByComparingTo("5");
            assertThat(p.tolerancePercent()).isEqualByComparingTo("2.5");
        });
    }

    @Test
    void withARateAnIbanStillNeedsPlausibleAmounts() throws Exception {
        setRate("EUR", "RON", "4.97", null);
        load(main, "RON", "2026-03-02,ORDIN PLATA " + TRAVEL_IBAN + ",-500.00");
        load(travel, "EUR", "2026-03-03,RAMBURSARE HOTEL,40.00"); // a refund, not the transfer

        assertThat(state(tx(travel))).isNull();
        assertThat(state(tx(main))).as("still a transfer by its IBAN, waiting for its other side")
                .isEqualTo("PROVISIONAL");
        assertThat(spent("RON")).isZero();
    }

    @Test
    void equalAmountsInOneCurrencyComeBeforeAConversion() throws Exception {
        long savings = account("Savings", "RO51TEST0000000000000003", "RON");
        setRate("EUR", "RON", "4.97", null);
        load(savings, "RON", "2026-03-03,INCASARE TRANSFER,500.00");
        exchange("ORDIN PLATA");

        assertThat(jdbc.queryForObject("SELECT transfer_account_id FROM transaction WHERE id = ?", Long.class, tx(main)))
                .isEqualTo(savings);
        assertThat(state(tx(travel))).isNull();
        assertThat(method()).isEqualTo("AMOUNT_DATE");
    }

    @Test
    void twoConversionsThatFitEquallyWellAreAskedAbout() throws Exception {
        long holiday = account("Holiday", "RO51TEST0000000000000003", "EUR");
        setRate("EUR", "RON", "4.97", null);
        exchange("ORDIN PLATA");
        load(holiday, "EUR", "2026-03-03,INCASARE TRANSFER,100.40");

        assertThat(state(tx(main))).isNull();
        List<ReviewCard> cards = http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == Kind.TRANSFER_TIE).toList();
        assertThat(cards).singleElement().satisfies(c -> {
            assertThat(c.transferTie().transactionId()).isEqualTo(tx(main));
            assertThat(c.currency()).isEqualTo("RON");
            assertThat(c.transferTie().options()).extracting(ReviewCard.TransferOption::accountName,
                            ReviewCard.TransferOption::amountMinor, ReviewCard.TransferOption::currency)
                    .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Travel", 10_050L, "EUR"),
                            org.assertj.core.groups.Tuple.tuple("Holiday", 10_040L, "EUR"));
        });

        var answer = http.postForEntity("/api/review/transfers/" + tx(main), Map.of("pairWith", tx(travel)), String.class);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(state(tx(travel))).isEqualTo("PAIRED");
        assertThat(state(tx(holiday))).isNull();
        assertThat(jdbc.queryForObject("SELECT source FROM transfer_pair", String.class)).isEqualTo("USER");
    }

    @Test
    void badRatesAreRefused() {
        assertThat(setRate("EUR", "EUR", "1", null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setRate("EUR", "XYZ", "1", null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setRate("eur", "RON", "4.97", null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setRate("EUR", "RON", "0", null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setRate("EUR", "RON", "-4.97", null)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setRate("EUR", "RON", "4.97", "11")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setRate("EUR", "RON", "4.97", "-1")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fx_rate", Long.class)).isZero();
    }

    @Test
    void aRateMatchesAmountsByTheirOwnDecimals() {
        var rate = new FxRates.Rate("EUR", "JPY", new BigDecimal("160"), new BigDecimal("1"));
        assertThat(rate.matches(-1_000, "EUR", 1_600, "JPY")).isTrue(); // 10.00 EUR = 1600 JPY (no decimals)
        assertThat(rate.matches(-1_000, "EUR", 1_700, "JPY")).isFalse(); // 6% away
        assertThat(rate.matches(-1_600, "JPY", 1_000, "EUR")).isTrue(); // either direction
        assertThat(rate.matches(-1_000, "EUR", 1_600, "RON")).isFalse(); // not this pair
    }
}
