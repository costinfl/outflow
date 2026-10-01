package dev.costinfl.outflow.ingest.parse.camt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.ingest.parse.FileSample;
import dev.costinfl.outflow.ingest.parse.ParsedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * CP6.18: the CAMT.053 parser against its golden file (expected values computed by hand, samples/synthetic/README.md)
 * and against hostile or inconsistent XML.
 */
class Camt053ParserTest {

    static final Path GOLDEN = Path.of("..", "samples", "synthetic", "camt053-2026-03.xml");

    final Camt053Parser parser = new Camt053Parser();

    ParsedStatement golden() throws IOException {
        try (InputStream in = Files.newInputStream(GOLDEN)) {
            return parser.parse(in);
        }
    }

    ParsedStatement parse(String xml) throws IOException {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static String statement(String iban, String entries) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt>
                <Stmt><Acct><Id><IBAN>%s</IBAN></Id><Ccy>EUR</Ccy></Acct>%s</Stmt>
                </BkToCstmrStmt></Document>""".formatted(iban, entries);
    }

    @Test
    void isDetectedByItsNamespaceAndNothingElseClaimsIt() throws IOException {
        byte[] bytes = Files.readAllBytes(GOLDEN);
        assertThat(parser.detect(FileSample.of("statement.xml", bytes)).value()).isEqualTo(0.95);
        assertThat(parser.detect(FileSample.of("x.csv", "Date,Description,Amount,Currency\n".getBytes())).value()).isZero();
        assertThat(parser.detect(FileSample.of("x.xml", "<a><BkToCstmrStmt/></a>".getBytes())).value()).isEqualTo(0.85);
    }

    @Test
    void theGoldenFileGivesItsRowsSumsAndAccount() throws IOException {
        var s = golden();

        assertThat(s.parserId()).isEqualTo("camt053-v1");
        assertThat(s.rows()).hasSize(8); // 9 entries, the INFO one is not money
        assertThat(s.rows().stream().mapToLong(ParsedRow::amountMinor).sum()).isEqualTo(494_791);
        assertThat(s.rows().stream().filter(r -> r.amountMinor() < 0).mapToLong(ParsedRow::amountMinor).sum())
                .isEqualTo(-305_209);
        assertThat(s.periodFrom()).contains(LocalDate.of(2026, 3, 2));
        assertThat(s.periodTo()).contains(LocalDate.of(2026, 3, 14));
        assertThat(s.accountHint()).hasValueSatisfying(h -> {
            assertThat(h.iban().value()).isEqualTo("RO49AAAA1B31007593840000");
            assertThat(h.currency()).contains("RON");
        });
        assertThat(s.rows()).extracting(ParsedRow::rowNo).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(s.rows()).extracting(ParsedRow::currency).containsOnly("RON");
    }

    @Test
    void entriesBecomeRowsWithTheirPartyReferenceAndStatus() throws IOException {
        var rows = golden().rows();

        var lidl = rows.get(0);
        assertThat(lidl.amountMinor()).isEqualTo(-4_510);
        assertThat(lidl.description()).isEqualTo("LIDL ROMANIA CUMPARARE POS LIDL 1234 BUCURESTI");
        assertThat(lidl.counterparty()).contains("LIDL ROMANIA");
        assertThat(lidl.reference()).contains("SYN-0001");
        assertThat(lidl.valueDate()).contains(LocalDate.of(2026, 3, 2));
        assertThat(lidl.payload()).containsExactly(Map.entry("BookgDt", "2026-03-02"), Map.entry("ValDt", "2026-03-02"),
                Map.entry("Amt", "45.10"), Map.entry("Ccy", "RON"), Map.entry("CdtDbtInd", "DBIT"), Map.entry("Sts", "BOOK"),
                Map.entry("AcctSvcrRef", "SYN-0001"), Map.entry("Cdtr", "LIDL ROMANIA"),
                Map.entry("Ustrd", "CUMPARARE POS LIDL 1234 BUCURESTI"));

        // The rent's creditor IBAN is in the description, where transfer pairing looks for own accounts.
        assertThat(rows.get(1).description()).isEqualTo("PROPRIETAR APARTAMENT RO22ANON0000000000000101 CHIRIE MARTIE");
        var salary = rows.get(2);
        assertThat(salary.amountMinor()).isEqualTo(800_000);
        assertThat(salary.counterparty()).contains("ACME SRL"); // money in: the debtor
        var netflix = rows.get(3); // 001.08 shapes: Sts/Cd and Cdtr/Pty/Nm
        assertThat(netflix.counterparty()).contains("NETFLIX.COM");
        assertThat(netflix.description()).isEqualTo("NETFLIX.COM NETFLIX ABONAMENT");
        assertThat(rows.get(4).reference()).contains("SYN-0005");
        assertThat(rows.get(5).reference()).contains("SYN-0006");
        var emag = rows.get(6);
        assertThat(emag.pending()).isTrue();
        assertThat(emag.reference()).as("a pending entry may come back booked: no identity reference").isEmpty();
        var atm = rows.get(7);
        assertThat(atm.bookingDate()).isEqualTo(LocalDate.of(2026, 3, 14)); // from DtTm
        assertThat(atm.description()).isEqualTo("RETRAGERE NUMERAR ATM");
        assertThat(atm.counterparty()).isEmpty();
    }

    @Test
    void amountsAreExactMinorUnitsOfTheirCurrency() {
        assertThat(Camt053Parser.minorUnits("12.3", "EUR")).isEqualTo(1_230);
        assertThat(Camt053Parser.minorUnits("1000", "JPY")).isEqualTo(1_000);
        assertThat(Camt053Parser.minorUnits("0.500", "KWD")).isEqualTo(500);
        assertThatThrownBy(() -> Camt053Parser.minorUnits("1.234", "EUR")).isInstanceOf(StatementParseException.class);
        assertThatThrownBy(() -> Camt053Parser.minorUnits("-5.00", "EUR")).isInstanceOf(StatementParseException.class);
        assertThatThrownBy(() -> Camt053Parser.minorUnits("5.00", "XXQ")).isInstanceOf(StatementParseException.class);
    }

    @Test
    void hostileOrInconsistentFilesAreRefused() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                <Ntry><Amt Ccy="EUR">1.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><BookgDt><Dt>2026-03-01</Dt></BookgDt>
                <AddtlNtryInf>&x;</AddtlNtryInf></Ntry></Stmt></BkToCstmrStmt></Document>""";
        assertThatThrownBy(() -> parse(xxe)).isInstanceOf(StatementParseException.class);

        String twoAccounts = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt>
                <Stmt><Acct><Id><IBAN>RO49AAAA1B31007593840000</IBAN></Id></Acct></Stmt>
                <Stmt><Acct><Id><IBAN>RO22ANON0000000000000101</IBAN></Id></Acct></Stmt>
                </BkToCstmrStmt></Document>""";
        assertThatThrownBy(() -> parse(twoAccounts)).isInstanceOf(StatementParseException.class)
                .hasMessageContaining("two accounts");

        assertThatThrownBy(() -> parse(statement("RO49AAAA1B31007593840000",
                "<Ntry><Amt Ccy=\"EUR\">1.00</Amt><CdtDbtInd>DBIT</CdtDbtInd></Ntry>")))
                .isInstanceOf(StatementParseException.class).hasMessageContaining("BookgDt");
        assertThatThrownBy(() -> parse("<html><body>not a statement</body></html>"))
                .isInstanceOf(StatementParseException.class);
    }

    @Test
    void aStatementWithoutEntriesIsEmptyButKeepsItsAccount() throws IOException {
        var s = parse(statement("RO49AAAA1B31007593840000", ""));

        assertThat(s.rows()).isEmpty();
        assertThat(s.periodFrom()).isEqualTo(Optional.empty());
        assertThat(s.accountHint()).isPresent();
    }
}
