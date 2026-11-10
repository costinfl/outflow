package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
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
 * CP7.3: three shops of one (made-up) chain, Zorba, none known to the keywords. Once the user puts one in Groceries,
 * the others are offered Groceries first; a keyword's guess teaches nothing; nothing is applied without an answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReviewSuggestionTest {

    @Autowired UploadService uploads;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long main = ImportFixtures.newAccount(jdbc, "Main");
        var csv = "Date,Description,Amount,Currency\r\n"
                + "2026-03-02,CUMPARARE POS ZORBA ALFA,-100.00,RON\r\n"
                + "2026-03-09,CUMPARARE POS ZORBA ALFA,-80.00,RON\r\n"
                + "2026-03-10,CUMPARARE POS ZORBA BETA,-60.00,RON\r\n"
                + "2026-03-11,CUMPARARE POS ZORBA GAMMA,-40.00,RON\r\n";
        uploads.importOne("march.csv", csv.getBytes(StandardCharsets.UTF_8), Optional.of(main), Optional.empty());
    }

    ReviewCard card(String name) {
        return http.getForObject("/api/review", Inbox.class).cards().stream()
                .filter(c -> c.kind() == Kind.UNCATEGORIZED_MERCHANT && c.name().equals(name)).findFirst().orElse(null);
    }

    long merchant(String key) {
        return jdbc.queryForObject("SELECT id FROM merchant WHERE key = ?", Long.class, key);
    }

    long category(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }

    void answer(String key, long categoryId) {
        http.postForEntity("/api/review/merchants/" + merchant(key) + "/category", Map.of("categoryId", categoryId),
                String.class);
    }

    @Test
    void anAnswerForOneShopIsSuggestedForTheOthers() {
        assertThat(card("Zorba Beta").suggestedCategoryId()).as("nothing known yet").isNull();

        answer("ZORBA ALFA", category("GROCERIES"));

        var market = card("Zorba Beta");
        assertThat(market.suggestedCategoryId()).isEqualTo(category("GROCERIES"));
        assertThat(market.suggestedCategoryName()).isEqualTo("Groceries");
        assertThat(market.suggestedBecause()).containsExactly("Zorba Alfa");
        assertThat(card("Zorba Gamma").suggestedCategoryId()).isEqualTo(category("GROCERIES"));
        // Only suggested: the shops stay uncategorized until the user answers.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE category_id IS NULL", Long.class))
                .isEqualTo(2);

        answer("ZORBA BETA", market.suggestedCategoryId());
        assertThat(card("Zorba Beta")).isNull();
        assertThat(card("Zorba Gamma").suggestedBecause()).containsExactly("Zorba Alfa", "Zorba Beta");
    }

    @Test
    void aKeywordsGuessTeachesNothing() {
        jdbc.update("""
                UPDATE transaction SET category_id = ?, category_source = 'KEYWORD', category_confidence = 0.70
                WHERE merchant_id = ?""", category("GROCERIES"), merchant("ZORBA ALFA"));

        assertThat(card("Zorba Beta").suggestedCategoryId()).isNull();
    }
}
