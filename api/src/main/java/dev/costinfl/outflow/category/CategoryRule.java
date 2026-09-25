package dev.costinfl.outflow.category;

/**
 * A category rule on the merchant key. USER rules are tier 1, SEED keyword rules tier 4. A rule with a
 * {@link Direction} applies only to money in that direction; without one (null) it applies to both.
 */
public record CategoryRule(Source source, int priority, MatchType matchType, String pattern, long categoryId,
        Direction direction) {

    /** A rule for both directions (every seed rule). */
    public CategoryRule(Source source, int priority, MatchType matchType, String pattern, long categoryId) {
        this(source, priority, matchType, pattern, categoryId, null);
    }

    public enum Source { USER, SEED }

    /** Money sent (a negative amount) or received (zero or positive). */
    public enum Direction {
        IN, OUT;

        public static Direction of(long amountMinor) {
            return amountMinor < 0 ? OUT : IN;
        }
    }

    public enum MatchType {
        /** The whole merchant key. */
        MERCHANT,
        /** Consecutive whole words anywhere in the merchant key: "MOL" matches "MOL 24", not "MOLDOVA". */
        KEYWORD
    }

    public boolean matches(String merchantKey, Direction money) {
        if (direction != null && direction != money) {
            return false;
        }
        return switch (matchType) {
            case MERCHANT -> merchantKey.equals(pattern);
            case KEYWORD -> (" " + merchantKey + " ").contains(" " + pattern + " ");
        };
    }
}
