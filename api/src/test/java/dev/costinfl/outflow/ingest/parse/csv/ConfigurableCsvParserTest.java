package dev.costinfl.outflow.ingest.parse.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConfigurableCsvParserTest {

    static ConfigurableCsvParser parser(String yaml) throws IOException {
        return new ConfigurableCsvParser(CsvProfileLoader.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml"));
    }

    static final String GENERIC = """
            id: t
            dateFormat: yyyy-MM-dd
            columns:
              bookingDate: Date
              amount: Amount
              currency: Currency
              description: [Description]
            """;

    static ParsedStatement parse(ConfigurableCsvParser p, String csv) throws IOException {
        return parse(p, csv, StandardCharsets.UTF_8);
    }

    static ParsedStatement parse(ConfigurableCsvParser p, String csv, Charset cs) throws IOException {
        return p.parse(new ByteArrayInputStream(csv.getBytes(cs)));
    }

    @Test
    void headerIsFoundAfterPreambleAndMatchedCaseInsensitively() throws IOException {
        var s = parse(parser(GENERIC), """
                Statement export
                Generated 2026-03-01

                date,DESCRIPTION , amount,Currency
                2026-02-01,LIDL,-10.00,RON
                """);

        assertThat(s.rows()).singleElement().extracting(ParsedRow::description).isEqualTo("LIDL");
    }

    @Test
    void blankLinesBetweenRowsAreSkippedButRowNumbersStayDense() throws IOException {
        var s = parse(parser(GENERIC), "Date,Description,Amount,Currency\n2026-02-01,A,-1.00,RON\n\n2026-02-02,B,-2.00,RON\n");

        assertThat(s.rows()).extracting(ParsedRow::rowNo).containsExactly(1, 2);
    }

    @Test
    void anUnreadableRowFailsTheWholeFileWithItsRowNumber() {
        assertThatThrownBy(() -> parse(parser(GENERIC),
                "Date,Description,Amount,Currency\n2026-02-01,A,-1.00,RON\n2026-02-30,B,-2.00,RON\n"))
                .isInstanceOf(StatementParseException.class)
                .hasMessage("Row 2: invalid booking date '2026-02-30'");
        assertThatThrownBy(() -> parse(parser(GENERIC), "Date,Description,Amount,Currency\n2026-02-01,A,lots,RON\n"))
                .hasMessage("Row 1: unreadable amount 'lots'");
        assertThatThrownBy(() -> parse(parser(GENERIC), "Date,Description,Amount,Currency\n2026-02-01,A,-1.00,ron\n"))
                .hasMessage("Row 1: invalid currency 'ron'");
    }

    @Test
    void extraNonEmptyCellsAreAnErrorNotSilentlyDropped() {
        assertThatThrownBy(() -> parse(parser(GENERIC), "Date,Description,Amount,Currency\n2026-02-01,A,-1.00,RON,surprise\n"))
                .hasMessageContaining("Row 1: has 5 cells, header has 4");
    }

    @Test
    void missingHeaderNamesTheExpectedColumns() {
        assertThatThrownBy(() -> parse(parser(GENERIC), "When,What,HowMuch\n"))
                .isInstanceOf(StatementParseException.class)
                .hasMessageContaining("[Date, Amount, Currency, Description]");
    }

    @Test
    void duplicateAndBlankHeaderNamesKeepEveryCell() throws IOException {
        var s = parse(parser(GENERIC), "Date,Description,Amount,Currency,Note,Note,\n2026-02-01,A,-1.00,RON,x,y,z\n");

        assertThat(s.rows().getFirst().payload())
                .containsEntry("Note", "x").containsEntry("Note_2", "y").containsEntry("column_7", "z");
    }

    @Test
    void multipleDescriptionColumnsAreJoined() throws IOException {
        var p = parser(GENERIC.replace("[Description]", "[Description, Payee]"));

        var s = parse(p, "Date,Description,Payee,Amount,Currency\n2026-02-01,Card payment, LIDL ,-1.00,RON\n2026-02-02,Fee,,-1.00,RON\n");

        assertThat(s.rows()).extracting(ParsedRow::description).containsExactly("Card payment LIDL", "Fee");
    }

    @Test
    void debitAndCreditMustNotBothBeFilled() {
        var yaml = """
                id: t
                dateFormat: dd.MM.yyyy
                decimalSeparator: ","
                currency: RON
                columns:
                  bookingDate: Data
                  debit: Debit
                  credit: Credit
                  description: [Detalii]
                """;
        assertThatThrownBy(() -> parse(parser(yaml), "Data;Detalii;Debit;Credit\n01.02.2026;x;1,00;2,00\n"))
                .hasMessageContaining("delimiter"); // wrong delimiter: header not found
        assertThatThrownBy(() -> parse(parser(yaml + "delimiter: ';'\n"), "Data;Detalii;Debit;Credit\n01.02.2026;x;1,00;2,00\n"))
                .hasMessage("Row 1: needs exactly one of debit or credit");
    }

    @Test
    void ibanColumnGivesTheAccountHintAndMustNotMixAccounts() throws IOException {
        var p = parser(GENERIC.replace("  description:", "  accountIban: Account\n  description:"));

        var s = parse(p, "Date,Account,Description,Amount,Currency\n2026-02-01,RO49AAAA1B31007593840000,A,-1.00,RON\n");
        assertThat(s.accountHint()).map(h -> h.iban().masked()).contains("RO49 •••• 0000");

        assertThatThrownBy(() -> parse(p, "Date,Account,Description,Amount,Currency\n"
                + "2026-02-01,RO49AAAA1B31007593840000,A,-1.00,RON\n2026-02-01,GB82WEST12345698765432,A,-1.00,RON\n"))
                .hasMessageContaining("mixes 2 accounts");
    }

    @Test
    void invalidEncodingFailsTheFile() throws IOException {
        var bytes = "Date,Description,Amount,Currency\n2026-02-01,Plată,-1.00,RON\n".getBytes(Charset.forName("windows-1250"));

        assertThatThrownBy(() -> parser(GENERIC).parse(new ByteArrayInputStream(bytes)))
                .hasMessageContaining("not valid UTF-8");
    }

    @Test
    void emptyFileWithHeaderHasNoRowsAndNoPeriod() throws IOException {
        var s = parse(parser(GENERIC), "Date,Description,Amount,Currency\n");

        assertThat(s.rows()).isEmpty();
        assertThat(s.periodFrom()).isEmpty();
    }

    @Test
    void detectionScores() throws IOException {
        var p = parser(GENERIC);
        String header = "Date,Description,Amount,Currency\n";

        assertThat(p.detect(sample(header + "2026-02-01,A,-1.00,RON\n2026-02-02,B,-2.00,RON\n")).value()).isEqualTo(1.0);
        assertThat(p.detect(sample(header + "01/02/2026,A,-1.00,RON\nx\n")).value()).isEqualTo(0.5);
        assertThat(p.detect(sample(header)).value()).isEqualTo(0.8);
        assertThat(p.detect(sample("Datum;Betrag\n")).value()).isZero();
        assertThat(p.detect(new FileSample("x.bin", new byte[] {(byte) 0xC3, 0x28, 0x0A})).value()).isZero();
    }

    static FileSample sample(String text) {
        return FileSample.of("x.csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void valueDateUsesItsOwnFormatWhenGiven() throws IOException {
        var p = parser(GENERIC.replace("  amount:", "  valueDate: Value\n  amount:") + "valueDateFormat: dd/MM/yyyy\n");

        var s = parse(p, "Date,Value,Description,Amount,Currency\n2026-02-01,03/02/2026,A,-1.00,RON\n2026-02-02,,B,-1.00,RON\n");

        assertThat(s.rows()).extracting(r -> r.valueDate().orElse(null))
                .containsExactly(LocalDate.of(2026, 2, 3), null);
    }
}
