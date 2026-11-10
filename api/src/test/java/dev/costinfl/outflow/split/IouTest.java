package dev.costinfl.outflow.split;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.insight.MonthSummary;
import dev.costinfl.outflow.split.IouController.IouCreditor;
import dev.costinfl.outflow.split.IouController.IouList;
import dev.costinfl.outflow.split.IouController.IouView;
import dev.costinfl.outflow.split.SettlementController.RepaymentSuggestion;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.23: "they paid, I owe". Ana paid a dinner (the user's share 100.00) and a cinema (30.00), Dan a taxi (45.00). In
 * March 2026 the user sends Ana 100.00, Dan 45.00, and shops at Kaufland.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class IouTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    long toAna, toDan, kaufland;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long main = ImportFixtures.newAccount(jdbc, "Main");
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-03-05,TRANSFER CATRE ANA IONESCU,-100.00,RON\r\n"
                + "2026-03-06,CUMPARARE POS KAUFLAND,-60.00,RON\r\n"
                + "2026-03-20,TRANSFER CATRE DAN,-45.00,RON\r\n";
        uploads.importOne("march.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());
        toAna = id("ANA IONESCU");
        toDan = id("CATRE DAN");
        kaufland = id("KAUFLAND");
        add("Ana Ionescu", "Dinner at La Mama", "2026-03-02", 10_000, "RESTAURANTS");
        add("Ana Ionescu", "Cinema", "2026-03-03", 3_000, "ENTERTAINMENT");
        add("Dan", "Taxi to the airport", "2026-03-18", 4_500, "TRANSPORT");
    }

    long id(String text) {
        return jdbc.queryForObject("SELECT id FROM transaction WHERE description_raw LIKE ?", Long.class, "%" + text + "%");
    }

    long category(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }

    ResponseEntity<String> add(String person, String what, String date, long amountMinor, String categoryCode) {
        var body = new HashMap<String, Object>();
        body.put("person", person);
        body.put("description", what);
        body.put("incurredOn", date);
        body.put("currency", "RON");
        body.put("amountMinor", amountMinor);
        body.put("categoryId", categoryCode == null ? null : category(categoryCode));
        return http.postForEntity("/api/ious", body, String.class);
    }

    List<IouCreditor> people() {
        return http.getForObject("/api/ious", IouList.class).people();
    }

    IouView iou(String description) {
        return people().stream().flatMap(p -> p.ious().stream()).filter(i -> i.description().equals(description))
                .findFirst().orElseThrow();
    }

    ResponseEntity<String> pay(long iouId, Long transactionId, Long amountMinor) {
        var body = new HashMap<String, Object>();
        body.put("iouId", iouId);
        body.put("transactionId", transactionId);
        body.put("amountMinor", amountMinor);
        return http.postForEntity("/api/ious/payments", body, String.class);
    }

    MonthSummary month() {
        return http.getForObject("/api/insights/month?month=2026-03", MonthSummary.class);
    }

    String categoryOf(long transactionId) {
        return jdbc.queryForObject("""
                SELECT c.code || '/' || t.category_source FROM transaction t JOIN category c ON c.id = t.category_id
                WHERE t.id = ?""", String.class, transactionId);
    }

    @Test
    void anIouCountsNowhereUntilMoneyMoves() {
        assertThat(people()).extracting(IouCreditor::name).containsExactly("Ana Ionescu", "Dan");
        assertThat(people().getFirst().openMinor()).isEqualTo(13_000);
        assertThat(people().getFirst().ious()).extracting(IouView::description).containsExactly("Cinema", "Dinner at La Mama");
        assertThat(month().spentMinor()).isEqualTo(20_500); // the three payments, nothing for the IOUs
    }

    @Test
    void suggestionsNameThePersonOrMatchTheAmount() {
        assertThat(iou("Dinner at La Mama").suggestions()).extracting(RepaymentSuggestion::transactionId)
                .first().isEqualTo(toAna);
        assertThat(iou("Dinner at La Mama").suggestions().getFirst().namesPerson()).isTrue();
        assertThat(iou("Dinner at La Mama").suggestions().getFirst().sameAmount()).isTrue();
        assertThat(iou("Taxi to the airport").suggestions()).extracting(RepaymentSuggestion::transactionId)
                .containsExactly(toDan);
    }

    @Test
    void theLinkedPaymentCountsAsWhatItWasFor() {
        long restaurants = category("RESTAURANTS");
        assertThat(month().spentMinor()).isEqualTo(20_500);

        assertThat(pay(iou("Dinner at La Mama").id(), toAna, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(categoryOf(toAna)).isEqualTo("RESTAURANTS/USER");
        var m = month();
        assertThat(m.spentMinor()).isEqualTo(20_500); // the same money, now in the right place
        assertThat(m.categories()).filteredOn(c -> Long.valueOf(restaurants).equals(c.categoryId())).singleElement()
                .satisfies(c -> assertThat(c.spentMinor()).isEqualTo(10_000));
        assertThat(iou("Dinner at La Mama").openMinor()).isZero();
        assertThat(people()).filteredOn(p -> p.name().equals("Ana Ionescu")).singleElement()
                .satisfies(p -> assertThat(p.openMinor()).isEqualTo(3_000));
        assertThat(iou("Cinema").suggestions()).extracting(RepaymentSuggestion::transactionId)
                .as("Ana's 100.00 is all used").doesNotContain(toAna);
    }

    @Test
    void aCategorySetByHandIsKept() {
        http.put("/api/transactions/" + toDan + "/category", Map.of("categoryId", category("OTHER")));

        assertThat(pay(iou("Taxi to the airport").id(), toDan, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(categoryOf(toDan)).isEqualTo("OTHER/USER");
    }

    @Test
    void partlyOutsideTheBankUndoAndRefusals() {
        long cinema = iou("Cinema").id();
        assertThat(pay(cinema, null, 1_000L).getStatusCode()).isEqualTo(HttpStatus.CREATED); // cash
        assertThat(pay(cinema, kaufland, 2_001L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // more than open
        assertThat(pay(cinema, kaufland, null).getStatusCode()).isEqualTo(HttpStatus.CREATED); // the 20.00 left
        assertThat(iou("Cinema").openMinor()).isZero();
        assertThat(pay(cinema, null, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // already paid
        assertThat(http.exchange("/api/ious/" + cinema, HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        long cash = iou("Cinema").payments().getFirst().id();
        assertThat(http.exchange("/api/ious/payments/" + cash, HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(iou("Cinema").openMinor()).isEqualTo(1_000);
        assertThat(http.exchange("/api/ious/payments/" + cash, HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(pay(999_999, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pay(iou("Taxi to the airport").id(), 999_999L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        long taxi = iou("Taxi to the airport").id();
        assertThat(http.exchange("/api/ious/" + taxi, HttpMethod.DELETE, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(people()).extracting(IouCreditor::name).containsExactly("Ana Ionescu");
    }

    @Test
    void anIouNeedsAPersonAnAmountAndAKnownCurrency() {
        assertThat(add(" ", "Dinner", "2026-03-02", 1_000, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add("Ana", "", "2026-03-02", 1_000, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add("Ana", "Dinner", "2026-03-02", 0, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = new HashMap<String, Object>(Map.of("person", "Ana", "description", "Dinner", "incurredOn", "2026-03-02",
                "currency", "XYZ", "amountMinor", 1_000));
        assertThat(http.postForEntity("/api/ious", body, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        body.put("currency", "RON");
        body.put("categoryId", 999_999);
        assertThat(http.postForEntity("/api/ious", body, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add("Ana", "Lunch", "2026-03-04", 2_500, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
