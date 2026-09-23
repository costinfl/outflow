package dev.costinfl.outflow.ingest;

import static dev.costinfl.outflow.ingest.ImportFixtures.identityKeys;
import static dev.costinfl.outflow.ingest.ImportFixtures.newAccount;
import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.parse.StatementDetector;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Property-style identity test (plan: Testing focus). For each seed: take the full Jan–Apr history, cut it into
 * overlapping files at random day boundaries, shuffle the rows inside each file, import the files in random order,
 * and some files twice. The transaction set must equal a single import of the whole history.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityPropertyTest {

    static final String HEADER = "Date,Description,Amount,Currency";

    @Autowired ImportService imports;
    @Autowired StatementDetector detector;
    @Autowired JdbcTemplate jdbc;

    StatementParser generic;
    List<String> history;
    List<String> baseline;

    @BeforeEach
    void setUp() throws Exception {
        generic = detector.byId("generic-csv-v1").orElseThrow();
        // Whole history = Jan–Mar file + the April rows of the Feb–Apr file (no overlap).
        history = new ArrayList<>(ImportFixtures.lines("generic-2026-01-to-03.csv").subList(1, 64));
        ImportFixtures.lines("generic-2026-02-to-04.csv").stream().filter(l -> l.startsWith("2026-04")).forEach(history::add);

        ImportFixtures.reset(jdbc);
        long account = newAccount(jdbc, "Main");
        imports.importFile(account, "all.csv", csv(history), generic);
        baseline = identityKeys(jdbc, account);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void anySplitOrderAndShuffleYieldsTheSameTransactions(long seed) {
        var random = new Random(seed);
        var files = randomOverlappingFiles(random);
        var order = new ArrayList<>(files);
        order.add(files.get(random.nextInt(files.size()))); // one file uploaded twice
        Collections.shuffle(order, random);

        ImportFixtures.reset(jdbc);
        long account = newAccount(jdbc, "Main");
        int i = 0;
        for (List<String> file : order) {
            imports.importFile(account, "part-" + i++ + ".csv", csv(file), generic);
        }

        assertThat(baseline).hasSize(84);
        assertThat(identityKeys(jdbc, account)).as("seed %d, %d files", seed, files.size()).isEqualTo(baseline);
    }

    /**
     * 2–5 files, each a contiguous date range, together covering every date; neighbours overlap by 0–20 days.
     * Cuts are at day boundaries: a statement never contains half of a day (see STATUS, spec question on mid-day cuts).
     */
    List<List<String>> randomOverlappingFiles(Random random) {
        var dates = new ArrayList<>(new TreeSet<>(history.stream().map(l -> LocalDate.parse(l.substring(0, 10))).toList()));
        int n = 2 + random.nextInt(4);
        var cuts = new TreeSet<Integer>();
        while (cuts.size() < n - 1) {
            cuts.add(1 + random.nextInt(dates.size() - 1));
        }
        var bounds = new ArrayList<Integer>(List.of(0));
        bounds.addAll(cuts);
        bounds.add(dates.size());

        var files = new ArrayList<List<String>>();
        for (int f = 0; f < n; f++) {
            int from = Math.max(0, bounds.get(f) - random.nextInt(8));
            int to = Math.min(dates.size(), bounds.get(f + 1) + random.nextInt(8));
            var range = new LinkedHashSet<>(dates.subList(from, to));
            var rows = new ArrayList<>(history.stream()
                    .filter(l -> range.contains(LocalDate.parse(l.substring(0, 10))))
                    .toList());
            Collections.shuffle(rows, random);
            files.add(rows);
        }
        return files;
    }

    static byte[] csv(List<String> rows) {
        return (HEADER + "\r\n" + String.join("\r\n", rows) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
