package dev.costinfl.outflow.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.ImportFixtures;
import dev.costinfl.outflow.ingest.UploadService;
import dev.costinfl.outflow.recurring.SubscriptionService;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CP6.4 on the user's anonymized 21-month ING export: transfers to people are most of the uncategorized money, and the
 * biggest person is money both ways. Answering the top people, one direction at a time, fixes most of the picture.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RealDataPeopleTest {

    static final Path FILE = Path.of("..", "samples", "ING Bank Romania", "Tranzactii_24-09-2026_11-36-40-anonymized.csv");

    @Autowired UploadService uploads;
    @Autowired ReviewService review;
    @Autowired CategoryService categories;
    @Autowired SubscriptionService subscriptions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void importRealExport() throws Exception {
        ImportFixtures.reset(jdbc);
        long account = ImportFixtures.newAccount(jdbc, "ING");
        uploads.importOne(FILE.getFileName().toString(), Files.readAllBytes(FILE), Optional.of(account), Optional.empty());
    }

    ReviewCard person(String name) {
        return review.inbox().cards().stream()
                .filter(c -> c.kind() == Kind.UNCATEGORIZED_MERCHANT && c.name().equals(name)).findFirst().orElseThrow();
    }

    /** Share of spending (money out that is not a transfer) with a category, in percent. */
    double coverage() {
        return jdbc.queryForObject("""
                SELECT 100.0 * coalesce(sum(-t.amount_minor) FILTER (WHERE t.category_id IS NOT NULL), 0) / sum(-t.amount_minor)
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.amount_minor < 0 AND coalesce(c.kind, 'SPEND') <> 'TRANSFER' AND t.transfer_state IS NULL""",
                Double.class);
    }

    /** Spending as the home screen counts it (Scope.SPEND), over the whole export. */
    long spent() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(-t.amount_minor), 0) FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE (c.kind = 'SPEND' OR (t.category_id IS NULL AND t.amount_minor < 0)) AND t.superseded_by IS NULL""",
                Long.class);
    }

    @Test
    void theBiggestPersonIsMoneyBothWays() {
        var p7 = person("Person_7");

        assertThat(p7.sentCount()).isEqualTo(90);
        assertThat(p7.sentMinor()).isEqualTo(34_932_100);
        assertThat(p7.receivedCount()).isEqualTo(264);
        assertThat(p7.receivedMinor()).isEqualTo(37_220_100);
        assertThat(p7.affectedMinor()).isEqualTo(p7.sentMinor() + p7.receivedMinor());
    }

    @Test
    void answeringTheTopPeopleByDirection() {
        double before = coverage();
        long spentBefore = spent();

        // Person_7: own money moving both ways. Person_4 and Person_3: money sent only, for something the user knows.
        categories.setMerchantCategory(person("Person_7").merchantId(), categories.categoryId("TRANSFER"), null);
        categories.setMerchantCategory(person("Person_4").merchantId(), categories.categoryId("HOUSING"), Direction.OUT);
        categories.setMerchantCategory(person("Person_3").merchantId(), categories.categoryId("OTHER"), Direction.OUT);
        subscriptions.refreshNow(); // as the review endpoint does: a transfer is no subscription

        double after = coverage();
        System.out.printf("Real ING export, top 3 people answered: spending categorized %.1f%% -> %.1f%%%n", before, after);
        assertThat(before).isLessThan(23.0);
        assertThat(after).isGreaterThanOrEqualTo(78.0);
        // Person_7's transfers leave spending; Person_4's and Person_3's stay in it, now categorized.
        assertThat(spent()).isEqualTo(spentBefore - 34_932_100);
        assertThat(review.inbox().cards()).noneMatch(c -> c.name().matches("Person_[347]"));
    }

    @Test
    void aSpendingAnswerForMoneySentNeverTurnsMoneyReceivedIntoRefunds() {
        long spentBefore = spent();

        categories.setMerchantCategory(person("Person_7").merchantId(), categories.categoryId("HOUSING"), Direction.OUT);

        // Before directions, "applies to all" made Person_7's 372,201 RON received a Housing refund.
        assertThat(spent()).isEqualTo(spentBefore);
        assertThat(person("Person_7").receivedMinor()).isEqualTo(37_220_100);
        assertThat(person("Person_7").sentCount()).isZero();
    }
}
