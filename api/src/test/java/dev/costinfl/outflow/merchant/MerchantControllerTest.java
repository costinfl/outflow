package dev.costinfl.outflow.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.merchant.MerchantController.AliasResult;
import dev.costinfl.outflow.merchant.MerchantController.Explanation;
import dev.costinfl.outflow.merchant.MerchantController.MerchantSummary;
import dev.costinfl.outflow.merchant.normalize.MerchantNormalizer;
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

/** CP2.3: the raw → merchant key debug view and user aliases. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MerchantControllerTest {

    @Autowired TestRestTemplate http;
    @Autowired UploadService uploads;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "Main");
        uploads.importOne("jan-mar.csv", ImportFixtures.sample("generic-2026-01-to-03.csv"), Optional.of(account), Optional.empty());
    }

    @Test
    void listShowsEveryMerchantWithCountsCategoryAndMaskedSamples() {
        var list = List.of(http.getForObject("/api/merchants", MerchantSummary[].class));

        assertThat(list).hasSize(11);
        assertThat(list.getFirst().transactionCount()).isEqualTo(12);
        assertThat(list).filteredOn(m -> m.key().equals("SPOTIFY")).singleElement().satisfies(m -> {
            assertThat(m.displayName()).isEqualTo("Spotify");
            assertThat(m.categoryCode()).isEqualTo("SUBSCRIPTIONS");
            assertThat(m.sampleDescriptions()).hasSize(3).allSatisfy(d -> assertThat(d).startsWith("CUMPARARE POS SPOTIFY"));
        });
        assertThat(list).filteredOn(m -> m.key().equals("CONT ECONOMII")).singleElement().satisfies(m ->
                assertThat(m.sampleDescriptions()).singleElement().asString()
                        .contains("RO49 •••• 0000").doesNotContain("RO49AAAA1B31007593840000"));
    }

    @Test
    void explainShowsEveryStepTheKeyAndTheCategoryTier() {
        var e = http.getForObject("/api/merchants/explain?raw={raw}", Explanation.class,
                "CUMPARARE POS SPOTIFY P770487 STOCKHOLM SE card ****4412");

        assertThat(e.steps()).extracting(MerchantNormalizer.Step::name).containsExactly(
                "BasicCleanup", "ChannelPrefix", "WebAddress", "VolatileTokens", "FillerWords", "TrailingLocation", "AliasStep");
        assertThat(e.steps().get(1).output()).isEqualTo("SPOTIFY P770487 STOCKHOLM SE CARD ****4412");
        assertThat(e.steps().get(3).output()).isEqualTo("SPOTIFY STOCKHOLM SE");
        assertThat(e.key()).isEqualTo("SPOTIFY");
        assertThat(e.categoryCode()).isEqualTo("SUBSCRIPTIONS");
        assertThat(e.categorySource()).isEqualTo("KEYWORD");

        assertThat(http.getForEntity("/api/merchants/explain?raw= ", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aUserAliasRenamesAMerchantAndItsCategoryFollows() {
        var r = http.postForObject("/api/merchants/aliases",
                Map.of("matchType", "EXACT", "pattern", "rent proprietar", "merchantKey", "Chirie"), AliasResult.class);

        assertThat(r.movedTransactions()).isEqualTo(3);
        // "CHIRIE" is a Housing keyword too, so the category is recomputed rather than lost
        assertThat(jdbc.queryForObject("""
                SELECT DISTINCT c.code || '/' || t.category_source FROM transaction t
                JOIN merchant m ON m.id = t.merchant_id JOIN category c ON c.id = t.category_id WHERE m.key = 'CHIRIE'""",
                String.class)).isEqualTo("HOUSING/KEYWORD");

        var bad = http.postForEntity("/api/merchants/aliases", Map.of("matchType", "EXACT", "pattern", " "), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
