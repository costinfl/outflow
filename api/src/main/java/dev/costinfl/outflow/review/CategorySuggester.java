package dev.costinfl.outflow.review;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A category to suggest for an uncategorized merchant (CP7.3), from what the user already answered for merchants
 * with a similar name. Only a suggestion: the user taps it, nothing is applied automatically. Pure and local: no data
 * leaves the machine.
 *
 * <p>Merchant keys are already normalized (upper case, no card or reference numbers). A key's words that can tell a
 * payee apart are those of 4+ letters, without digits, not a filler word, and not common (in more than
 * {@value #COMMON} merchant keys: cities, "ROMANIA", "SRL"). A suggestion is made when at least 2 known merchants
 * sharing such a word agree on a category and no other category has as many, or when exactly 1 known merchant shares
 * the first word, which names the brand ("MEGA IMAGE 0123" after "MEGA IMAGE CLUJ").
 */
public final class CategorySuggester {

    static final int COMMON = 10;

    /** A merchant the user categorized (by hand, a rule or learned edits), for one direction of money. */
    public record Known(String key, String name, long categoryId, String categoryName) {}

    /** The category to offer first, and up to two merchants it comes from ("Like Mega Image and Lidl"). */
    public record Suggestion(long categoryId, String categoryName, List<String> because) {}

    private final Map<String, Integer> frequency = new HashMap<>();
    private final Set<String> filler;

    /** @param allKeys every merchant key, to tell common words from the ones that name a payee */
    public CategorySuggester(Collection<String> allKeys, Set<String> filler) {
        this.filler = filler;
        for (String key : allKeys) {
            new HashSet<>(List.of(key.split(" "))).forEach(w -> frequency.merge(w, 1, Integer::sum));
        }
    }

    /** The words of a key that can tell a payee apart, in order. */
    List<String> words(String key) {
        var out = new ArrayList<String>();
        for (String w : key.split(" ")) {
            if (w.length() >= 4 && w.chars().allMatch(Character::isLetter) && !filler.contains(w)
                    && frequency.getOrDefault(w, 0) <= COMMON && !out.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    public Optional<Suggestion> suggest(String key, List<Known> known) {
        if (key.startsWith("PERSON_")) {
            return Optional.empty(); // an anonymized person: a name says nothing about what the money was for
        }
        List<String> words = words(key);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        var matches = known.stream().filter(k -> !k.key().equals(key))
                .filter(k -> words(k.key()).stream().anyMatch(words::contains)).toList();
        // Votes per category, in the order the matches came (the caller lists the most used merchants first).
        var votes = new LinkedHashMap<Long, List<Known>>();
        matches.forEach(m -> votes.computeIfAbsent(m.categoryId(), c -> new ArrayList<>()).add(m));
        int best = votes.values().stream().mapToInt(List::size).max().orElse(0);
        var leaders = votes.values().stream().filter(v -> v.size() == best).toList();
        if (best >= 2 && leaders.size() == 1) {
            return Optional.of(suggestion(leaders.getFirst()));
        }
        String brand = key.split(" ")[0];
        var sameBrand = matches.stream().filter(m -> m.key().split(" ")[0].equals(brand) && words.contains(brand)).toList();
        if (sameBrand.size() == 1 && votes.size() == 1) {
            return Optional.of(suggestion(sameBrand));
        }
        return Optional.empty();
    }

    private static Suggestion suggestion(List<Known> from) {
        var names = from.stream().map(Known::name).distinct().limit(2).toList();
        return new Suggestion(from.getFirst().categoryId(), from.getFirst().categoryName(), names);
    }
}
