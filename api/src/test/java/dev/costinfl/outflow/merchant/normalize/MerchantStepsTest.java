package dev.costinfl.outflow.merchant.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each normalization step on its own (DESIGN: "each a unit-tested function"). */
class MerchantStepsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Plată  POS  Kaufland      | PLATA POS KAUFLAND",
        "BUCUREŞTI Ștefan ţ ț      | BUCURESTI STEFAN T T",
        "'\"McDonald''s\" Unirii'  | MCDONALDS UNIRII",
    })
    void basicCleanup(String in, String out) {
        assertThat(new BasicCleanup().apply(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "CUMPARARE POS KAUFLAND 381   | KAUFLAND 381",
        "PLATA POS LIDL               | LIDL",
        "PLATA CARD EMAG              | EMAG",
        "PAYPAL *STEAM GAMES          | STEAM GAMES",
        "PAYPAL*SPOTIFY               | SPOTIFY",
        "POS ONLINE GLOVO             | GLOVO",
        "TRANSFER CATRE CONT ECONOMII | CONT ECONOMII",
        "PLATA RENT                   | RENT",
        "POSTA ROMANA                 | POSTA ROMANA",
        "ONLINER SRL                  | ONLINER SRL",
    })
    void channelPrefix(String in, String out) {
        assertThat(new ChannelPrefix().apply(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "NETFLIX.COM 1234567           | NETFLIX 1234567",
        "BOLT.EU/R/123456 TALLINN EE   | BOLT TALLINN EE",
        "WWW.EMAG.RO BUCURESTI         | EMAG BUCURESTI",
        "AMAZON.DE                     | AMAZON",
        "AMAZON.CO.UK*AB12             | AMAZON*AB12",
        "DR. OETKER                    | DR. OETKER",
    })
    void webAddress(String in, String out) {
        assertThat(new WebAddress().apply(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "KAUFLAND 381 CARD ****4412 AUTORIZARE 356787 | KAUFLAND",
        "LIDL XXXX4412                                | LIDL",
        "EMAG 541234******1234                        | EMAG",
        "CONT ECONOMII RO49AAAA1B31007593840000       | CONT ECONOMII",
        "BOLT 12.01.2026 14:05                        | BOLT",
        "AMZN MKTP DE*2B4XY7Z                         | AMZN MKTP DE",
        "SPOTIFY P770487 STOCKHOLM                    | SPOTIFY STOCKHOLM",
        "OMV 7123 # 2                                 | OMV",
        "MAXX STORE                                   | MAXX STORE",
    })
    void volatileTokens(String in, String out) {
        assertThat(new VolatileTokens().apply(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "RENT PROPRIETAR IANUARIE    | RENT PROPRIETAR",
        "RENT PROPRIETAR LUNA        | RENT PROPRIETAR",
        "ENEL ENERGIE FACTURA NR     | ENEL ENERGIE",
        "ORANGE ROMANIA ABONAMENT    | ORANGE ROMANIA",
        "MAIB                        | MAIB",
    })
    void fillerWords(String in, String out) {
        assertThat(new FillerWords().apply(in)).isEqualTo(out);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "KAUFLAND BUCURESTI         | KAUFLAND",
        "SPOTIFY STOCKHOLM SE       | SPOTIFY",
        "ORANGE ROMANIA             | ORANGE",
        "MEGA IMAGE SECTOR          | MEGA IMAGE",
        "ROMANIA                    | ROMANIA",
        "STARBUCKS AFI COTROCENI    | STARBUCKS AFI COTROCENI",
        "RO                         | RO",
    })
    void trailingLocation(String in, String out) {
        assertThat(new TrailingLocation().apply(in)).isEqualTo(out);
    }

    @Test
    void aliasesExactFirstThenLongestPrefixOnWordBoundaries() {
        var step = new AliasStep(List.of(
                new Alias(Alias.MatchType.PREFIX, "AMZN", "AMAZON"),
                new Alias(Alias.MatchType.PREFIX, "AMZN MKTP", "AMAZON MARKETPLACE"),
                new Alias(Alias.MatchType.EXACT, "AMZN MKTP PRIME", "AMAZON PRIME"),
                new Alias(Alias.MatchType.PREFIX, "lidl", "LIDL")));

        assertThat(step.apply("AMZN MKTP PRIME")).isEqualTo("AMAZON PRIME");
        assertThat(step.apply("AMZN MKTP DE")).isEqualTo("AMAZON MARKETPLACE");
        assertThat(step.apply("AMZN DIGITAL")).isEqualTo("AMAZON");
        assertThat(step.apply("LIDL DISCOUNT")).isEqualTo("LIDL");
        assertThat(step.apply("LIDLX")).isEqualTo("LIDLX");
        assertThat(step.apply("KAUFLAND")).isEqualTo("KAUFLAND");
    }
}
