package dev.costinfl.outflow.ingest.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IbanTest {

    @Test
    void validIbanInElectronicOrPrintedForm() {
        assertThat(Iban.parse("RO49AAAA1B31007593840000")).map(Iban::value).contains("RO49AAAA1B31007593840000");
        assertThat(Iban.parse("ro49 aaaa 1b31 0075 9384 0000")).map(Iban::value).contains("RO49AAAA1B31007593840000");
        assertThat(Iban.parse("GB82WEST12345698765432")).isPresent();
    }

    @Test
    void wrongChecksumOrShapeIsRejected() {
        assertThat(Iban.parse("RO48AAAA1B31007593840000")).isEmpty();
        assertThat(Iban.parse("RO49")).isEmpty();
        assertThat(Iban.parse(null)).isEmpty();
    }

    @Test
    void findsIbanInFreeText() {
        assertThat(Iban.find("IBAN: RO49 AAAA 1B31 0075 9384 0000 (RON)")).map(Iban::value)
                .contains("RO49AAAA1B31007593840000");
        assertThat(Iban.find("Cont RO48AAAA1B31007593840000 invalid")).isEmpty();
    }

    @Test
    void maskShowsCountryCheckDigitsAndLastFourOnly() {
        var iban = Iban.parse("RO49AAAA1B31007593840000").orElseThrow();

        assertThat(iban.masked()).isEqualTo("RO49 •••• 0000");
        assertThat(iban.toString()).isEqualTo(iban.masked());
        assertThat(new AccountHint(iban, java.util.Optional.of("RON")).toString())
                .doesNotContain("RO49AAAA1B31007593840000");
    }
}
