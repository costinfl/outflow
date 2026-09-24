package dev.costinfl.outflow.merchant.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser;
import dev.costinfl.outflow.ingest.parse.csv.CsvProfileLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MerchantNormalizerTest {

    /** A few of the seeded aliases (V3 migration), enough for the golden table. */
    static final MerchantNormalizer NORMALIZER = new MerchantNormalizer(List.of(
            new Alias(Alias.MatchType.PREFIX, "STARBUCKS", "STARBUCKS"),
            new Alias(Alias.MatchType.PREFIX, "SPOTIFY", "SPOTIFY"),
            new Alias(Alias.MatchType.PREFIX, "AMZN MKTP", "AMAZON"),
            new Alias(Alias.MatchType.PREFIX, "AMAZON", "AMAZON"),
            new Alias(Alias.MatchType.PREFIX, "MEGA IMAGE", "MEGA IMAGE")));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // the synthetic samples
        "CUMPARARE POS KAUFLAND BUCURESTI 381 card ****4412 autorizare 356787 | KAUFLAND",
        "Plată POS KAUFLAND BUCUREŞTI 263 card ****4412 autorizare 832052      | KAUFLAND",
        "CUMPARARE POS LIDL 1234 BUCURESTI card ****4412                      | LIDL",
        "CUMPARARE POS SPOTIFY P770487 STOCKHOLM SE card ****4412             | SPOTIFY",
        "NETFLIX.COM 1234567 AMSTERDAM NL card ****4412                       | NETFLIX",
        "BOLT.EU/R/123456 TALLINN EE                                          | BOLT",
        "ENEL ENERGIE FACTURA 123456789                                       | ENEL ENERGIE",
        "ORANGE ROMANIA ABONAMENT                                             | ORANGE",
        "PLATA RENT PROPRIETAR IANUARIE                                       | RENT PROPRIETAR",
        "PLATA RENT PROPRIETAR LUNA 02                                        | RENT PROPRIETAR",
        "INCASARE SALARIU ACME SRL                                            | SALARIU ACME SRL",
        "TRANSFER CATRE CONT ECONOMII RO49AAAA1B31007593840000                | CONT ECONOMII",
        "CUMPARARE POS STARBUCKS AFI COTROCENI card ****4412                  | STARBUCKS",
        // typical real-world shapes
        "POS 12/01/2026 14:05 MEGA IMAGE 0123 BUCURESTI RO                    | MEGA IMAGE",
        "PLATA CARD 4412 EMAG.RO BUCURESTI                                    | EMAG",
        "PAYPAL *STEAM GAMES 4029357733 LU                                    | STEAM GAMES",
        "AMZN MKTP DE*2B4XY7Z                                                 | AMAZON",
        "AMAZON.DE                                                            | AMAZON",
        "OMV PETROM 7123 CLUJ-NAPOCA RO                                       | OMV PETROM",
        "GLOVO*BUCURESTI                                                      | GLOVO",
    })
    void goldenKeys(String raw, String key) {
        assertThat(NORMALIZER.key(raw)).isEqualTo(key);
    }

    @Test
    void pureNoiseKeepsItsCleanedTextInsteadOfBecomingEmpty() {
        assertThat(NORMALIZER.key("POS 12.01.2026 ****4412")).isEqualTo("POS 12.01.2026 ****4412");
        assertThat(NORMALIZER.key("   ")).isEqualTo("UNKNOWN");
    }

    @Test
    void displayNames() {
        assertThat(MerchantNormalizer.displayName("ENEL ENERGIE")).isEqualTo("Enel Energie");
        assertThat(MerchantNormalizer.displayName("KAUFLAND")).isEqualTo("Kaufland");
    }

    /** One merchant, one key: every recurring payee in four months of samples collapses to a single key. */
    @Test
    void samplesCollapseToOneKeyPerMerchant() throws Exception {
        var rows = new java.util.ArrayList<ParsedRow>();
        for (String file : List.of("generic-2026-01-to-03.csv", "generic-2026-02-to-04.csv")) {
            rows.addAll(parse("/parsers/generic-csv-v1.yml", file));
        }
        rows.addAll(parse("/test-parsers/ro-style-csv-v1.yml", "ro-style-2026-02.csv"));

        Map<String, Long> perKey = rows.stream()
                .collect(Collectors.groupingBy(r -> NORMALIZER.key(r.description()), Collectors.counting()));

        assertThat(new TreeSet<>(perKey.keySet())).containsExactly(
                "BOLT", "CONT ECONOMII", "ENEL ENERGIE", "KAUFLAND", "LIDL", "NETFLIX", "ORANGE", "RENT PROPRIETAR",
                "SALARIU ACME SRL", "SPOTIFY", "STARBUCKS");
    }

    static List<ParsedRow> parse(String profile, String file) throws Exception {
        try (InputStream yaml = MerchantNormalizerTest.class.getResourceAsStream(profile);
                InputStream in = Files.newInputStream(Path.of("..", "samples", "synthetic", file))) {
            return new ConfigurableCsvParser(CsvProfileLoader.load(yaml, profile)).parse(in).rows();
        }
    }
}
