package dev.costinfl.outflow.merchant.normalize;

import java.util.Locale;

/**
 * One alias rule. {@code EXACT} matches the whole key; {@code PREFIX} matches the key's leading words
 * ("STARBUCKS" matches "STARBUCKS AFI COTROCENI" but not "STARBUCKSX").
 */
public record Alias(MatchType matchType, String pattern, String merchantKey) {

    public enum MatchType { EXACT, PREFIX }

    public Alias {
        pattern = new BasicCleanup().apply(pattern);
        merchantKey = merchantKey.strip().toUpperCase(Locale.ROOT);
        if (pattern.isEmpty() || merchantKey.isEmpty()) {
            throw new IllegalArgumentException("alias pattern and merchant key must not be empty");
        }
    }

    boolean matches(String key) {
        return switch (matchType) {
            case EXACT -> key.equals(pattern);
            case PREFIX -> key.equals(pattern) || key.startsWith(pattern + " ");
        };
    }
}
