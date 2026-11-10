package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP7.2: what the one-question-at-a-time review needs besides the cards: the categories in the order the user needs
 * them (most used first), and how much of all spending has a category, which moves with every answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReviewProgressTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long main = ImportFixtures.newAccount(jdbc, "Main");
        // Three groceries payments (a keyword knows Lidl), one café and one unknown shop: 300 of 400 categorized.
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-03-02,CUMPARARE POS LIDL,-100.00,RON\r\n"
                + "2026-03-09,CUMPARARE POS LIDL,-50.00,RON\r\n"
                + "2026-03-16,CUMPARARE POS LIDL,-100.00,RON\r\n"
                + "2026-03-17,CUMPARARE POS STARBUCKS,-50.00,RON\r\n"
                + "2026-03-20,CUMPARARE POS ZZ WIDGETS,-100.00,RON\r\n";
        uploads.importOne("march.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());
    }

    Inbox inbox() {
        return http.getForObject("/api/review", Inbox.class);
    }

    long category(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }

    @Test
    void theMostUsedCategoriesComeFirstAndEveryCategoryIsListed() {
        var order = inbox().categoryOrder();

        assertThat(order.getFirst()).isEqualTo(category("GROCERIES"));
        assertThat(order.get(1)).isEqualTo(category("RESTAURANTS"));
        assertThat(order).hasSize(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).doesNotHaveDuplicates();
    }

    @Test
    void theShareOfSpendingWithACategoryMovesWithEachAnswer() {
        assertThat(inbox().categorizedPct()).isEqualTo(75); // 300.00 of 400.00

        long widgets = jdbc.queryForObject("SELECT id FROM merchant WHERE key = 'ZZ WIDGETS'", Long.class);
        http.postForEntity("/api/review/merchants/" + widgets + "/category", Map.of("categoryId", category("SHOPPING")),
                String.class);

        assertThat(inbox().categorizedPct()).isEqualTo(100);
    }

    @Test
    void withNoSpendingThereIsNothingLeftToCategorize() {
        ImportFixtures.reset(jdbc);
        assertThat(inbox().categorizedPct()).isEqualTo(100);
    }
}
