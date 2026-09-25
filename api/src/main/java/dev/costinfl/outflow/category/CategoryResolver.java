package dev.costinfl.outflow.category;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Category resolution for one merchant, first match wins (DESIGN: Category resolution). Implemented tiers:
 * 1 user rule → 2 learned from confirmations → 4 keyword seed rules → 6 uncategorized. Tier 3 (MCC) needs a statement
 * format that carries MCC codes; tier 5 (LLM) is out of scope. A manual edit on a single transaction is also tier 1
 * and is handled by never recomputing {@link Source#USER} transactions.
 */
public final class CategoryResolver {

    /** Where a transaction's category came from; stored as {@code transaction.category_source}. */
    public enum Source {
        USER(new BigDecimal("1.00")),
        RULE(new BigDecimal("1.00")),
        LEARNED(new BigDecimal("0.95")),
        MCC(new BigDecimal("0.85")),
        KEYWORD(new BigDecimal("0.70")),
        SYSTEM(new BigDecimal("1.00"));

        public final BigDecimal confidence;

        Source(BigDecimal confidence) {
            this.confidence = confidence;
        }
    }

    public record Resolution(long categoryId, Source source) {

        public BigDecimal confidence() {
            return source.confidence;
        }
    }

    /** Within a tier: explicit priority (lower first), then the more specific (longer) pattern. */
    private static final Comparator<CategoryRule> ORDER = Comparator.comparingInt(CategoryRule::priority)
            .thenComparing(r -> r.direction() == null) // a rule for this direction before one for both
            .thenComparing(r -> r.matchType() != CategoryRule.MatchType.MERCHANT)
            .thenComparing(r -> -r.pattern().length())
            .thenComparing(CategoryRule::pattern);

    private final List<CategoryRule> userRules;
    private final List<CategoryRule> seedRules;

    public CategoryResolver(List<CategoryRule> rules) {
        this.userRules = rules.stream().filter(r -> r.source() == CategoryRule.Source.USER).sorted(ORDER).toList();
        this.seedRules = rules.stream().filter(r -> r.source() == CategoryRule.Source.SEED).sorted(ORDER).toList();
    }

    /** Resolution for money sent: what a merchant's spending is. */
    public Optional<Resolution> resolve(String merchantKey, Long learnedCategoryId) {
        return resolve(merchantKey, learnedCategoryId, CategoryRule.Direction.OUT);
    }

    /**
     * @param learnedCategoryId the merchant's category learned from user confirmations ({@code merchant.default_category_id})
     * @param money money sent or received: user rules may apply to one direction only
     */
    public Optional<Resolution> resolve(String merchantKey, Long learnedCategoryId, CategoryRule.Direction money) {
        var user = first(userRules, merchantKey, money);
        if (user.isPresent()) {
            return Optional.of(new Resolution(user.get().categoryId(), Source.RULE));
        }
        if (learnedCategoryId != null) {
            return Optional.of(new Resolution(learnedCategoryId, Source.LEARNED));
        }
        return first(seedRules, merchantKey, money).map(r -> new Resolution(r.categoryId(), Source.KEYWORD));
    }

    private static Optional<CategoryRule> first(List<CategoryRule> rules, String merchantKey, CategoryRule.Direction money) {
        return rules.stream().filter(r -> r.matches(merchantKey, money)).findFirst();
    }
}
