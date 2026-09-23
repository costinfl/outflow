package dev.costinfl.outflow.ingest.parse.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvProfileTest {

    private static CsvProfile load(String yaml) throws IOException {
        return CsvProfileLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    private static final String VALID = """
            id: my-bank-v1
            dateFormat: dd/MM/yyyy
            currency: EUR
            columns:
              bookingDate: Date
              amount: Amount
              description: [Details, Payee]
            """;

    @Test
    void defaultsAndRequiredColumns() throws IOException {
        var p = load(VALID);

        assertThat(p.encoding()).isEqualTo("UTF-8");
        assertThat(p.delimiter()).isEqualTo(",");
        assertThat(p.decimalSeparator()).isEqualTo(".");
        assertThat(p.valueDateFormat()).isEqualTo("dd/MM/yyyy");
        assertThat(p.columns().required()).containsExactly("Date", "Amount", "Details", "Payee");
    }

    @Test
    void unknownKeysAreRejectedSoTyposAreNotSilent() {
        assertThatThrownBy(() -> load(VALID.replace("amount:", "amonut:")))
                .hasMessageContaining("test.yml").hasMessageContaining("amonut");
    }

    @Test
    void amountXorDebitCredit() {
        assertThatThrownBy(() -> load(VALID.replace("  amount: Amount", "  amount: Amount\n  debit: D\n  credit: C")))
                .hasMessageContaining("either columns.amount or columns.debit");
        assertThatThrownBy(() -> load(VALID.replace("  amount: Amount", "  debit: D")))
                .hasMessageContaining("go together");
    }

    @Test
    void exactlyOneCurrencySource() {
        assertThatThrownBy(() -> load(VALID.replace("currency: EUR\n", "")))
                .hasMessageContaining("exactly one of currency");
        assertThatThrownBy(() -> load(VALID.replace("  amount: Amount", "  amount: Amount\n  currency: Ccy")))
                .hasMessageContaining("exactly one of currency");
    }

    @Test
    void invalidSettingsFailAtLoadNotAtImport() {
        assertThatThrownBy(() -> load(VALID.replace("id: my-bank-v1", "id: My Bank"))).hasMessageContaining("id must be");
        assertThatThrownBy(() -> load(VALID + "encoding: NOPE-42\n")).hasMessageContaining("unsupported encoding");
        assertThatThrownBy(() -> load(VALID + "delimiter: ';;'\n")).hasMessageContaining("delimiter");
        assertThatThrownBy(() -> load(VALID.replace("currency: EUR", "currency: EURO"))).isInstanceOf(IllegalArgumentException.class);
    }
}
