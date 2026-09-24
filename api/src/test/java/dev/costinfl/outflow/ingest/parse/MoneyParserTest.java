package dev.costinfl.outflow.ingest.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MoneyParserTest {

    static final Currency RON = Currency.getInstance("RON");
    static final Currency JPY = Currency.getInstance("JPY");

    final MoneyParser dotDecimal = new MoneyParser('.', null);
    final MoneyParser european = new MoneyParser(',', '.');

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "-49.99    | -4999",
        "8500.00   | 850000",
        "8500      | 850000",
        "0.5       | 50",
        "+12.30    | 1230",
        "' 18.50 ' | 1850",
        "18.50-    | -1850",
    })
    void dotDecimals(String text, long minor) {
        assertThat(dotDecimal.toMinor(text, RON)).isEqualTo(minor);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1.234,56     | 123456",
        "1234,56      | 123456",
        "2.500,00     | 250000",
        "-1.000.000,01| -100000001",
        "29,99        | 2999",
        "'1 234,56'   | 123456",
    })
    void europeanDecimals(String text, long minor) {
        assertThat(european.toMinor(text, RON)).isEqualTo(minor);
    }

    @Test
    void noBreakSpaceIsFormattingOnly() {
        assertThat(european.toMinor("1 234,56", RON)).isEqualTo(123456);
    }

    @Test
    void currencyDecimalsDecideTheMinorUnit() {
        assertThat(dotDecimal.toMinor("1500", JPY)).isEqualTo(1500);
        assertThatThrownBy(() -> dotDecimal.toMinor("1500.5", JPY)).hasMessageContaining("more than 0 decimals");
    }

    @Test
    void moreDecimalsThanTheCurrencyHasIsAnError() {
        assertThatThrownBy(() -> dotDecimal.toMinor("1.999", RON)).hasMessageContaining("more than 2 decimals");
    }

    @ParameterizedTest
    @CsvSource(value = {"'1,234.56'", "'12,34'", "1.23.4", "abc", "--5", "''", "'-'"})
    void dotDecimalParserRejectsMalformed(String text) {
        assertThatThrownBy(() -> dotDecimal.toMinor(text, RON)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(value = {"'1.234,5,6'", "'12.3.4,00'", "'1.23,00'", "abc"})
    void europeanParserRejectsMalformed(String text) {
        assertThatThrownBy(() -> european.toMinor(text, RON)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void europeanParserRejectsDotDecimal() {
        // With ',' as decimal, "12.34" can only be a malformed grouping, never 12.34.
        assertThatThrownBy(() -> european.toMinor("12.34", RON)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowIsAnErrorNotAWrap() {
        assertThatThrownBy(() -> dotDecimal.toMinor("99999999999999999999", RON)).hasMessageContaining("out of range");
    }
}
