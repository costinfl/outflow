package dev.costinfl.outflow.ingest.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementDetectorTest {

    final StatementDetector detector;

    StatementDetectorTest() throws IOException {
        detector = new StatementDetector(List.of(
                GoldenFileTest.parser("/parsers/generic-csv-v1.yml"),
                GoldenFileTest.parser("/test-parsers/ro-style-csv-v1.yml")));
    }

    static FileSample sampleOf(String file) throws IOException {
        return FileSample.of(file, Files.readAllBytes(GoldenFileTest.SAMPLES.resolve(file)));
    }

    @Test
    void picksTheMatchingParserForEachSample() throws IOException {
        assertThat(detector.detect(sampleOf("generic-2026-01-to-03.csv")).chosen())
                .map(StatementParser::id).contains("generic-csv-v1");
        assertThat(detector.detect(sampleOf("ro-style-2026-02.csv")).chosen())
                .map(StatementParser::id).contains("ro-style-csv-v1");
    }

    @Test
    void unknownFileChoosesNothingButExplainsEveryCandidate() {
        var d = detector.detect(FileSample.of("x.csv", "Datum;Betrag\n1;2\n".getBytes()));

        assertThat(d.chosen()).isEmpty();
        assertThat(d.candidates()).hasSize(2).allSatisfy(c -> assertThat(c.score().reason()).isNotBlank());
    }

    @Test
    void aTieAtTheTopIsNotGuessed() throws IOException {
        var twin = new StatementDetector(List.of(
                GoldenFileTest.parser("/parsers/generic-csv-v1.yml"),
                new dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser(
                        ((dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser)
                                GoldenFileTest.parser("/parsers/generic-csv-v1.yml")).profile().withId("generic-twin"))));

        var d = twin.detect(sampleOf("generic-2026-01-to-03.csv"));

        assertThat(d.chosen()).isEmpty();
        assertThat(d.candidates()).extracting(c -> c.score().value()).containsExactly(1.0, 1.0);
    }

    @Test
    void userCanOverrideByIdAndIdsAreUnique() throws IOException {
        assertThat(detector.byId("ro-style-csv-v1")).isPresent();
        assertThat(detector.byId("nope")).isEmpty();
        assertThat(detector.parsers()).extracting(StatementParser::id).containsExactly("generic-csv-v1", "ro-style-csv-v1");

        var p = GoldenFileTest.parser("/parsers/generic-csv-v1.yml");
        assertThatThrownBy(() -> new StatementDetector(List.of(p, p))).hasMessageContaining("duplicate parser id");
    }
}
