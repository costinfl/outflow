package dev.costinfl.outflow.category;

/** A category rule on the merchant key. USER rules are tier 1, SEED keyword rules tier 4. */
public record CategoryRule(Source source, int priority, MatchType matchType, String pattern, long categoryId) {

    public enum Source { USER, SEED }

    public enum MatchType {
        /** The whole merchant key. */
        MERCHANT,
        /** Consecutive whole words anywhere in the merchant key: "MOL" matches "MOL 24", not "MOLDOVA". */
        KEYWORD
    }

    public boolean matches(String merchantKey) {
        return switch (matchType) {
            case MERCHANT -> merchantKey.equals(pattern);
            case KEYWORD -> (" " + merchantKey + " ").contains(" " + pattern + " ");
        };
    }
}
