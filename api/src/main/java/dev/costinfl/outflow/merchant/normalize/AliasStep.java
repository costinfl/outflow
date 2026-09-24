package dev.costinfl.outflow.merchant.normalize;

import java.util.Comparator;
import java.util.List;

/** Step 5: the alias table. Exact matches first, then the longest matching prefix. */
public final class AliasStep implements MerchantStep {

    private final List<Alias> ordered;

    public AliasStep(List<Alias> aliases) {
        this.ordered = aliases.stream()
                .sorted(Comparator.comparing((Alias a) -> a.matchType() != Alias.MatchType.EXACT)
                        .thenComparing(a -> -a.pattern().length()))
                .toList();
    }

    @Override
    public String apply(String text) {
        return ordered.stream().filter(a -> a.matches(text)).findFirst().map(Alias::merchantKey).orElse(text);
    }
}
