package dev.costinfl.outflow.category;

import static dev.costinfl.outflow.category.CategoryRule.MatchType.KEYWORD;
import static dev.costinfl.outflow.category.CategoryRule.MatchType.MERCHANT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.category.CategoryResolver.Resolution;
import dev.costinfl.outflow.category.CategoryResolver.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryResolverTest {

    static final long GROCERIES = 1, RESTAURANTS = 2, FUEL = 4, SHOPPING = 11, OTHER = 18;

    static CategoryRule seed(String keyword, long category) {
        return new CategoryRule(CategoryRule.Source.SEED, 100, KEYWORD, keyword, category);
    }

    static CategoryRule user(CategoryRule.MatchType type, String pattern, long category) {
        return new CategoryRule(CategoryRule.Source.USER, 100, type, pattern, category);
    }

    final CategoryResolver seedsOnly = new CategoryResolver(List.of(
            seed("KAUFLAND", GROCERIES), seed("MOL", FUEL), seed("MEGA IMAGE", GROCERIES), seed("MARKET", GROCERIES),
            seed("COFFEE", RESTAURANTS), seed("COFFEE MARKET", RESTAURANTS)));

    @Test
    void keywordsMatchWholeWordsOnly() {
        assertThat(seedsOnly.resolve("MOL", null)).contains(new Resolution(FUEL, Source.KEYWORD));
        assertThat(seedsOnly.resolve("MOL EXPRES", null)).contains(new Resolution(FUEL, Source.KEYWORD));
        assertThat(seedsOnly.resolve("MOLDOVA TRADE", null)).isEmpty();
        assertThat(seedsOnly.resolve("MEGA IMAGE", null)).map(Resolution::categoryId).contains(GROCERIES);
        assertThat(seedsOnly.resolve("MEGA IMAGINE", null)).isEmpty();
    }

    @Test
    void theMoreSpecificKeywordWins() {
        assertThat(seedsOnly.resolve("THE COFFEE MARKET", null)).map(Resolution::categoryId).contains(RESTAURANTS);
    }

    @Test
    void tiersInOrderUserRuleThenLearnedThenKeyword() {
        var resolver = new CategoryResolver(List.of(seed("KAUFLAND", GROCERIES), user(MERCHANT, "KAUFLAND", SHOPPING)));

        assertThat(resolver.resolve("KAUFLAND", OTHER)).contains(new Resolution(SHOPPING, Source.RULE));
        assertThat(seedsOnly.resolve("KAUFLAND", OTHER)).contains(new Resolution(OTHER, Source.LEARNED));
        assertThat(seedsOnly.resolve("KAUFLAND", null)).contains(new Resolution(GROCERIES, Source.KEYWORD));
        assertThat(seedsOnly.resolve("SOMEWHERE NEW", null)).isEmpty();
    }

    @Test
    void confidencesFollowTheDesignTable() {
        assertThat(Source.RULE.confidence).isEqualByComparingTo("1.00");
        assertThat(Source.LEARNED.confidence).isEqualByComparingTo("0.95");
        assertThat(Source.MCC.confidence).isEqualByComparingTo("0.85");
        assertThat(Source.KEYWORD.confidence).isEqualByComparingTo("0.70");
    }

    @Test
    void merchantRuleBeatsKeywordRuleWithinTheUserTier() {
        var resolver = new CategoryResolver(List.of(user(KEYWORD, "KAUFLAND", GROCERIES), user(MERCHANT, "KAUFLAND", SHOPPING)));

        assertThat(resolver.resolve("KAUFLAND", null)).map(Resolution::categoryId).contains(SHOPPING);
    }
}
