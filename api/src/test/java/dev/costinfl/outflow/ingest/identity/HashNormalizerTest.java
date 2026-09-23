package dev.costinfl.outflow.ingest.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HashNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // uppercase, diacritics (cedilla and comma-below forms), whitespace
        "Plată  POS   Kaufland          | PLATA POS KAUFLAND",
        "BUCUREŞTI ȘTEFAN ţ ț           | BUCURESTI STEFAN T T",
        "'  café\tcrème  '              | CAFE CREME",
        // card masks
        "STARBUCKS card ****4412        | STARBUCKS CARD",
        "LIDL XXXX4412 RO               | LIDL RO",
        "EMAG 541234******1234          | EMAG",
        "EMAG 5412 **** **** 1234       | EMAG",
        // authorization codes
        "KAUFLAND autorizare 832052     | KAUFLAND",
        "KAUFLAND AUTH CODE: A1B2C3     | KAUFLAND",
        "KAUFLAND Cod autorizare 123456 | KAUFLAND",
        // times and dates
        "BOLT 14:05 2026-01-12          | BOLT",
        "BOLT 14:05:33 12.01.2026       | BOLT",
        "BOLT 12/01/2026 ride           | BOLT RIDE",
        // non-volatile numbers stay: store numbers, invoice numbers
        "LIDL 1234 BUCURESTI            | LIDL 1234 BUCURESTI",
        "ENEL FACTURA 123456789         | ENEL FACTURA 123456789",
    })
    void normalizes(String raw, String expected) {
        assertThat(HashNormalizer.normalize(raw)).isEqualTo(expected);
    }
}
