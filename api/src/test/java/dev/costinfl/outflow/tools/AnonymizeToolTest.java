package dev.costinfl.outflow.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.ingest.parse.Iban;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser;
import dev.costinfl.outflow.ingest.parse.csv.CsvProfileLoader;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of {@code tools/Anonymize.java}, run exactly as a user runs it ({@code java tools/Anonymize.java}):
 * personal data is gone, and the file still parses to the same rows, dates and amounts.
 */
class AnonymizeToolTest {

    static final Charset CP1250 = Charset.forName("windows-1250");
    static final Path TOOL = Path.of("..", "tools", "Anonymize.java");

    /** A fake "raw" export, Romanian-bank style, stuffed with every kind of personal data the tool handles. */
    static final String RAW = String.join("\r\n",
            "Extras de cont",
            "Titular:;Ştefan Ionescu",
            "IBAN:;RO49AAAA1B31007593840000",
            "Email:;stefan.ionescu@mail.ro",
            "",
            "Data tranzactie;Data valuta;Descriere;Debit;Credit",
            "02.02.2026;03.02.2026;\"Plată POS KAUFLAND BUCUREŞTI card ****4412 autorizare 832052\";356,96;",
            "03.02.2026;04.02.2026;\"Plată card 4111 1111 1111 1111 EMAG.RO\";1.299,00;",
            "05.02.2026;05.02.2026;\"Transfer către IONESCU STEFAN RO49 AAAA 1B31 0075 9384 0000 economii\";1.000,00;",
            "06.02.2026;06.02.2026;\"Transfer de la Maria Pop tel 0722 123 456 CNP 1800101221144\";;250,00",
            "07.02.2026;07.02.2026;\"ENEL ENERGIE cod client 1234567890123 factura\";210,01;",
            "");

    @TempDir
    Path dir;

    record Run(String output, String report) {}

    Run anonymize(String seed, String... extra) throws Exception {
        Path in = dir.resolve("raw.csv");
        Files.write(in, RAW.getBytes(CP1250));
        Path out = dir.resolve("out-" + seed + ".csv");
        var cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"),
                TOOL.toString(), "--in", in.toString(), "--out", out.toString(), "--seed", seed));
        cmd.addAll(List.of(extra));
        var process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String report = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(report).isZero();
        return new Run(new String(Files.readAllBytes(out), CP1250), report);
    }

    static ParsedStatement parse(String csv) throws Exception {
        try (InputStream yaml = AnonymizeToolTest.class.getResourceAsStream("/test-parsers/ro-style-csv-v1.yml")) {
            return new ConfigurableCsvParser(CsvProfileLoader.load(yaml, "ro-style"))
                    .parse(new java.io.ByteArrayInputStream(csv.getBytes(CP1250)));
        }
    }

    @Test
    void personalDataIsGone() throws Exception {
        Path names = dir.resolve("names.txt");
        Files.writeString(names, "# people I transfer money to\nMaria Pop\n");

        String out = anonymize("s1", "--names", names.toString()).output();

        assertThat(out).doesNotContain("Ştefan", "Stefan Ionescu", "IONESCU STEFAN", "Maria", "Pop ",
                "RO49AAAA1B31007593840000", "RO49 AAAA", "4412", "4111 1111", "1800101221144",
                "stefan.ionescu@mail.ro", "0722 123 456", "1234567890123");
        assertThat(out).contains("Titular:;PERSON_1", "Transfer către PERSON_1", "Transfer de la PERSON_2",
                "person1@example.invalid", "EMAG.RO", "KAUFLAND BUCUREŞTI");
    }

    @Test
    void theFileStillParsesToTheSameRowsDatesAndAmounts() throws Exception {
        var before = parse(RAW);
        var after = parse(anonymize("s1").output());

        assertThat(after.rows()).hasSameSizeAs(before.rows());
        assertThat(after.rows()).extracting(ParsedRow::bookingDate, ParsedRow::valueDate, ParsedRow::amountMinor)
                .containsExactlyElementsOf(before.rows().stream()
                        .map(r -> org.assertj.core.groups.Tuple.tuple(r.bookingDate(), r.valueDate(), r.amountMinor()))
                        .toList());
        // the account is still detected, from a fake but checksum-valid IBAN
        assertThat(after.accountHint()).hasValueSatisfying(h -> {
            assertThat(h.iban().value()).startsWith("RO").contains("ANON");
            assertThat(Iban.parse(h.iban().value())).isPresent();
        });
    }

    @Test
    void onlyTheReplacedTokensChange() throws Exception {
        String out = anonymize("s1").output();

        var rawLines = RAW.split("\r\n", -1);
        var outLines = out.split("\r\n", -1);
        assertThat(outLines).hasSameSizeAs(rawLines);
        for (int i = 0; i < rawLines.length; i++) {
            assertThat(outLines[i].chars().filter(c -> c == ';').count())
                    .as("delimiters on line %d", i).isEqualTo(rawLines[i].chars().filter(c -> c == ';').count());
        }
        assertThat(outLines[6]).isEqualTo(rawLines[6].replace("4412", outLines[6].replaceAll(".*\\*{4}(\\d{4}).*", "$1")));
    }

    @Test
    void sameSeedSameFakesAcrossRunsAndTheSameIbanEverywhere() throws Exception {
        String a = anonymize("s1").output();
        String b = anonymize("s1").output();
        String c = anonymize("s2").output();

        assertThat(a).isEqualTo(b);
        assertThat(c).isNotEqualTo(a);
        String preamble = a.lines().filter(l -> l.startsWith("IBAN:;")).findFirst().orElseThrow().substring(6);
        String spaced = preamble.replaceAll("(.{4})(?!$)", "$1 ");
        assertThat(a).contains(spaced); // the spaced IBAN in the transfer row maps to the same fake
    }

    /**
     * The golden files shared with the browser version (web/src/anonymize, checked by web/test/anonymize.test.mjs):
     * both implementations must write exactly these bytes for the same input, seed and names.
     */
    @Test
    void goldenFilesSharedWithTheBrowserVersion() throws Exception {
        Path fixtures = Path.of("..", "web", "test", "anonymize");
        for (String name : List.of("ro-cp1250", "generic-utf8-bom")) {
            Path out = dir.resolve("golden-" + name + ".csv");
            var process = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), TOOL.toString(),
                    "--in", fixtures.resolve("raw-" + name + ".csv").toString(), "--out", out.toString(),
                    "--seed", "parity", "--names", fixtures.resolve("names.txt").toString())
                    .redirectErrorStream(true).start();
            String report = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor()).as(report).isZero();
            assertThat(Files.readAllBytes(out)).as(name)
                    .isEqualTo(Files.readAllBytes(fixtures.resolve("expected-" + name + ".csv")));
        }
    }

    @Test
    void theReportListsReplacementsAndLinesToReview() throws Exception {
        String report = anonymize("s1").report();

        assertThat(report).contains("IBAN", "card number", "card digits", "CNP", "email", "phone", "long reference")
                .contains("look like transfers")
                .doesNotContain("RO49AAAA1B31007593840000", "1800101221144", "stefan.ionescu@mail.ro");
    }
}
