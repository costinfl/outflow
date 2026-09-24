package dev.costinfl.outflow.merchant.normalize;

import java.util.List;

/**
 * Raw bank description → merchant key (DESIGN: Merchant normalization and classification). The key is what
 * categories and recurrence key on. Unlike the identity {@code key_v1}, it is an interpretation: it may improve over
 * time, and every transaction's merchant can be recomputed from its raw description.
 */
public final class MerchantNormalizer {

    private final BasicCleanup basic = new BasicCleanup();
    private final List<MerchantStep> steps;

    public MerchantNormalizer(List<Alias> aliases) {
        this.steps = List.of(
                basic,
                new ChannelPrefix(),
                new WebAddress(),
                new VolatileTokens(),
                new FillerWords(),
                new TrailingLocation(),
                new AliasStep(aliases));
    }

    public String key(String rawDescription) {
        String s = rawDescription;
        for (MerchantStep step : steps) {
            s = step.apply(s);
        }
        // A description that is nothing but noise keeps its cleaned text rather than collapsing into "".
        return s.isEmpty() ? fallback(rawDescription) : s;
    }

    /** One line per step, for the raw → key debug view. */
    public record Step(String name, String output) {}

    public List<Step> trace(String rawDescription) {
        var out = new java.util.ArrayList<Step>();
        String s = rawDescription;
        for (MerchantStep step : steps) {
            s = step.apply(s);
            out.add(new Step(step.getClass().getSimpleName(), s));
        }
        if (s.isEmpty()) {
            out.add(new Step("Fallback", fallback(rawDescription)));
        }
        return out;
    }

    private String fallback(String raw) {
        String cleaned = basic.apply(raw);
        return cleaned.isEmpty() ? "UNKNOWN" : cleaned.length() > 60 ? cleaned.substring(0, 60).strip() : cleaned;
    }

    /** "KAUFLAND" → "Kaufland", "ENEL ENERGIE" → "Enel Energie": the default display name, renamable later. */
    public static String displayName(String key) {
        var out = new StringBuilder();
        for (String word : key.toLowerCase(java.util.Locale.ROOT).split(" ")) {
            if (!word.isEmpty()) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
            }
        }
        return out.toString().strip();
    }
}
