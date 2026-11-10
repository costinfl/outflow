package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.SweepMerchant;
import java.nio.charset.StandardCharsets;
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
 * CP7.4: "sweep the small ones". March 2026: 3,000.00 of groceries, 60.00 at a shop worth its own question, four
 * small shops (10, 20, 2 x 15, 45), a person and a two-way merchant. Spending is 3,180.00, so the four small shops
 * (105.00, 3%) can be answered together; the bigger shop, the person and the two-way merchant cannot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReviewSweepTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long main = ImportFixtures.newAccount(jdbc, "Main");
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-03-02,CUMPARARE POS LIDL,-3000.00,RON\r\n"
                + "2026-03-03,CUMPARARE POS ZORBA ALFA,-60.00,RON\r\n"
                + "2026-03-04,CUMPARARE POS ZZ ONE,-10.00,RON\r\n"
                + "2026-03-05,CUMPARARE POS ZZ TWO,-20.00,RON\r\n"
                + "2026-03-06,CUMPARARE POS ZZ THREE,-15.00,RON\r\n"
                + "2026-03-07,CUMPARARE POS ZZ THREE,-15.00,RON\r\n"
                + "2026-03-08,CUMPARARE POS ZZ FOUR,-45.00,RON\r\n"
                + "2026-03-09,PERSON_9,-5.00,RON\r\n"
                + "2026-03-10,CUMPARARE POS ZZ BOTH,-10.00,RON\r\n"
                + "2026-03-11,INCASARE ZZ BOTH,5.00,RON\r\n";
        uploads.importOne("march.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());
    }

    Inbox inbox() {
        return http.getForObject("/api/review", Inbox.class);
    }

    long category(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }

    String categoryOf(String merchantKey) {
        return String.join(",", jdbc.queryForList("""
                SELECT coalesce(c.code, '-') FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                LEFT JOIN category c ON c.id = t.category_id WHERE m.key = ? ORDER BY t.id""", String.class, merchantKey));
    }

    @Test
    void theSmallOneWayMerchantsCanBeAnsweredTogether() {
        var sweep = inbox().sweep();

        assertThat(sweep.merchants()).extracting(SweepMerchant::name)
                .containsExactly("Zz Four", "Zz Three", "Zz Two", "Zz One"); // largest first
        assertThat(sweep.totalMinor()).isEqualTo(10_500);
        assertThat(sweep.sharePct()).isEqualTo(3);
        assertThat(sweep.eachUnderMinor()).isEqualTo(5_000);
        assertThat(sweep.currency()).isEqualTo("RON");
        assertThat(sweep.merchants()).filteredOn(m -> m.name().equals("Zz Three")).singleElement()
                .satisfies(m -> assertThat(m.count()).isEqualTo(2));
    }

    @Test
    void aSweepAnswersThemAllKeepsHandSetCategoriesAndCanBeUndone() {
        long three = jdbc.queryForObject("SELECT min(t.id) FROM transaction t JOIN merchant m ON m.id = t.merchant_id "
                + "WHERE m.key = 'ZZ THREE'", Long.class);
        http.put("/api/transactions/" + three + "/category", Map.of("categoryId", category("HEALTH")));
        var sweep = inbox().sweep();
        assertThat(sweep.merchants()).filteredOn(m -> m.name().equals("Zz Three")).singleElement()
                .satisfies(m -> assertThat(m.sentMinor()).isEqualTo(1_500));

        var body = Map.of("merchantIds", sweep.merchantIds(), "categoryId", category("OTHER"));
        var r = http.postForEntity("/api/review/merchants/sweep", body, ReviewController.SweepResult.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().merchants()).isEqualTo(4);

        assertThat(categoryOf("ZZ ONE")).isEqualTo("OTHER");
        assertThat(categoryOf("ZZ THREE")).isEqualTo("HEALTH,OTHER"); // the hand-set one is kept
        assertThat(categoryOf("ZORBA ALFA")).isEqualTo("-");
        assertThat(categoryOf("ZZ BOTH")).isEqualTo("-,-");
        assertThat(inbox().sweep()).isNull();

        var undo = http.postForEntity("/api/review/merchants/sweep/undo", body, ReviewController.SweepResult.class);
        assertThat(undo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(categoryOf("ZZ ONE")).isEqualTo("-");
        assertThat(categoryOf("ZZ THREE")).isEqualTo("HEALTH,-");
        assertThat(inbox().sweep().merchantIds()).containsExactlyInAnyOrderElementsOf(sweep.merchantIds());
    }

    @Test
    void tooFewSmallMerchantsOrABadAnswer() {
        var ids = inbox().sweep().merchantIds();
        assertThat(http.postForEntity("/api/review/merchants/sweep", Map.of("merchantIds", List.of(), "categoryId",
                category("OTHER")), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/review/merchants/sweep", Map.of("merchantIds", ids, "categoryId", 999_999),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/review/merchants/sweep", Map.of("merchantIds", List.of(999_999),
                "categoryId", category("OTHER")), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Two answered one by one: two small ones left are answered one by one too.
        for (long id : ids.subList(0, 2)) {
            http.postForEntity("/api/review/merchants/" + id + "/category", Map.of("categoryId", category("SHOPPING")),
                    String.class);
        }
        assertThat(inbox().sweep()).isNull();
    }
}
