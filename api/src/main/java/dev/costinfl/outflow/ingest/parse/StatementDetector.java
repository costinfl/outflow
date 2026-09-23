package dev.costinfl.outflow.ingest.parse;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Picks the parser for a file by sniffing it; the user can override by parser id. */
public class StatementDetector {

    /** Below this, a parser's claim is not trusted enough to import with. */
    public static final double THRESHOLD = 0.75;

    public record Candidate(StatementParser parser, DetectionScore score) {}

    /** All scores (best first) and the chosen parser, if exactly one clears the threshold at the top. */
    public record Detection(Optional<StatementParser> chosen, List<Candidate> candidates) {}

    private final Map<String, StatementParser> parsers;

    public StatementDetector(List<StatementParser> parsers) {
        this.parsers = parsers.stream().collect(Collectors.toMap(StatementParser::id, Function.identity(), (a, b) -> {
            throw new IllegalStateException("duplicate parser id " + a.id());
        }));
    }

    public List<StatementParser> parsers() {
        return parsers.values().stream().sorted(Comparator.comparing(StatementParser::id)).toList();
    }

    public Optional<StatementParser> byId(String id) {
        return Optional.ofNullable(parsers.get(id));
    }

    public Detection detect(FileSample sample) {
        var candidates = parsers().stream()
                .map(p -> new Candidate(p, p.detect(sample)))
                .sorted(Comparator.comparing(Candidate::score).reversed())
                .toList();
        if (candidates.isEmpty() || candidates.getFirst().score().value() < THRESHOLD) {
            return new Detection(Optional.empty(), candidates);
        }
        boolean tie = candidates.size() > 1
                && candidates.get(1).score().value() == candidates.getFirst().score().value();
        return new Detection(tie ? Optional.empty() : Optional.of(candidates.getFirst().parser()), candidates);
    }
}
