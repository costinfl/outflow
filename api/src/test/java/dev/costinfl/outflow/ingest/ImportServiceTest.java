package dev.costinfl.outflow.ingest;

import static dev.costinfl.outflow.ingest.ImportFixtures.count;
import static dev.costinfl.outflow.ingest.ImportFixtures.identityKeys;
import static dev.costinfl.outflow.ingest.ImportFixtures.newAccount;
import static dev.costinfl.outflow.ingest.ImportFixtures.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.TestcontainersConfiguration;
import dev.costinfl.outflow.ingest.parse.StatementDetector;
import dev.costinfl.outflow.ingest.parse.StatementParseException;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP1.3 / M1 acceptance: every transaction imported exactly once, whatever the upload pattern. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ImportServiceTest {

    static final String JAN_MAR = "generic-2026-01-to-03.csv";
    static final String FEB_APR = "generic-2026-02-to-04.csv";
    /** Union of both files, computed independently from the CSVs (see samples/synthetic/README.md). */
    static final int UNION_ROWS = 84;
    static final long UNION_SUM = 1_243_801;

    @Autowired ImportService imports;
    @Autowired StatementDetector detector;
    @Autowired JdbcTemplate jdbc;

    StatementParser generic;
    long account;

    @BeforeEach
    void setUp() {
        ImportFixtures.reset(jdbc);
        generic = detector.byId("generic-csv-v1").orElseThrow();
        account = newAccount(jdbc, "Main");
    }

    ImportResult load(String file) throws Exception {
        return imports.importFile(account, file, sample(file), generic);
    }

    @Test
    void firstImportCreatesEveryRow() throws Exception {
        var r = load(JAN_MAR);

        assertThat(r.duplicateFile()).isFalse();
        assertThat(r.rows()).isEqualTo(63);
        assertThat(r.newTransactions()).isEqualTo(63);
        assertThat(r.alreadyImported()).isZero();
        assertThat(r.periodFrom()).contains(LocalDate.of(2026, 1, 1));
        assertThat(r.periodTo()).contains(LocalDate.of(2026, 3, 26));
        assertThat(count(jdbc, "transaction", account)).isEqualTo(63);
        assertThat(count(jdbc, "raw_row", account)).isEqualTo(63);
        assertThat(count(jdbc, "transaction_source", account)).isEqualTo(63);
        assertThat(jdbc.queryForObject("SELECT format FROM statement_file WHERE account_id = ?", String.class, account))
                .isEqualTo("generic-csv-v1");
    }

    @Test
    void reUploadingTheSameFileIsANoOp() throws Exception {
        load(JAN_MAR);
        var before = identityKeys(jdbc, account);

        var again = load(JAN_MAR);

        assertThat(again.duplicateFile()).isTrue();
        assertThat(again.newTransactions()).isZero();
        assertThat(again.statementFileId()).isEmpty();
        assertThat(identityKeys(jdbc, account)).isEqualTo(before);
        assertThat(count(jdbc, "statement_file", account)).isEqualTo(1);
        assertThat(count(jdbc, "raw_row", account)).isEqualTo(63);
    }

    @Test
    void overlappingUploadsGiveExactlyTheUnion() throws Exception {
        load(JAN_MAR);
        var second = load(FEB_APR);

        assertThat(second.newTransactions()).isEqualTo(UNION_ROWS - 63);
        assertThat(second.alreadyImported()).isEqualTo(42);
        assertThat(count(jdbc, "transaction", account)).isEqualTo(UNION_ROWS);
        assertThat(jdbc.queryForObject("SELECT sum(amount_minor) FROM transaction WHERE account_id = ?", Long.class, account))
                .isEqualTo(UNION_SUM);
        // every raw row of both files is kept and linked to exactly one transaction
        assertThat(count(jdbc, "raw_row", account)).isEqualTo(126);
        assertThat(count(jdbc, "transaction_source", account)).isEqualTo(126);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM transaction t WHERE account_id = ?
                  AND NOT EXISTS (SELECT 1 FROM transaction_source s WHERE s.transaction_id = t.id)""",
                Long.class, account)).isZero();
    }

    @Test
    void importOrderDoesNotMatter() throws Exception {
        load(JAN_MAR);
        load(FEB_APR);
        var forward = identityKeys(jdbc, account);

        ImportFixtures.reset(jdbc);
        account = newAccount(jdbc, "Main"); // same id again after RESTART IDENTITY, so keys are comparable
        load(FEB_APR);
        load(JAN_MAR);

        assertThat(identityKeys(jdbc, account)).hasSize(UNION_ROWS).isEqualTo(forward);
    }

    @Test
    void identicalSameDayRowsAreTwoTransactionsStableAcrossReImports() throws Exception {
        load(JAN_MAR);
        String coffees = "Date,Description,Amount,Currency\r\n"
                + "2026-01-12,CUMPARARE POS STARBUCKS AFI COTROCENI card ****4412,-18.50,RON\r\n".repeat(2);

        var r = imports.importFile(account, "coffees.csv", coffees.getBytes(StandardCharsets.UTF_8), generic);

        assertThat(r.newTransactions()).isZero();
        assertThat(r.alreadyImported()).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT identity_key FROM transaction
                WHERE account_id = ? AND booking_date = '2026-01-12' AND description_raw LIKE '%STARBUCKS%'
                ORDER BY identity_key""", String.class, account))
                .hasSize(2)
                .satisfiesExactly(k -> assertThat(k).endsWith("#1"), k -> assertThat(k).endsWith("#2"));
    }

    @Test
    void aThirdIdenticalRowSeenLaterIsNew() throws Exception {
        load(JAN_MAR); // two coffees on Jan 12
        String threeCoffees = "Date,Description,Amount,Currency\r\n"
                + "2026-01-12,CUMPARARE POS STARBUCKS AFI COTROCENI card ****4412,-18.50,RON\r\n".repeat(3);

        var r = imports.importFile(account, "three.csv", threeCoffees.getBytes(StandardCharsets.UTF_8), generic);

        assertThat(r.newTransactions()).isEqualTo(1);
        assertThat(r.alreadyImported()).isEqualTo(2);
    }

    @Test
    void sameFileInAnotherAccountIsImportedThere() throws Exception {
        load(JAN_MAR);
        long savings = newAccount(jdbc, "Savings");

        var r = imports.importFile(savings, JAN_MAR, sample(JAN_MAR), generic);

        assertThat(r.duplicateFile()).isFalse();
        assertThat(r.newTransactions()).isEqualTo(63);
        assertThat(count(jdbc, "transaction", account)).isEqualTo(63);
    }

    @Test
    void anUnreadableFileStoresNothing() {
        byte[] broken = "Date,Description,Amount,Currency\r\n2026-01-01,A,-1.00,RON\r\n2026-13-01,B,-1.00,RON\r\n"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> imports.importFile(account, "broken.csv", broken, generic))
                .isInstanceOf(StatementParseException.class)
                .hasMessageContaining("Row 2");
        assertThat(count(jdbc, "statement_file", account)).isZero();
        assertThat(count(jdbc, "transaction", account)).isZero();
    }

    @Test
    void aFailureAfterWritesRollsBackTheWholeFile() {
        // A reference that repeats with different content is detected after the statement_file row is written.
        var withRefs = new dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser(
                ((dev.costinfl.outflow.ingest.parse.csv.ConfigurableCsvParser) generic).profile().withReference("Ref"));
        byte[] clash = "Date,Description,Amount,Currency,Ref\r\n2026-01-01,A,-1.00,RON,R1\r\n2026-01-02,B,-2.00,RON,R1\r\n"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> imports.importFile(account, "clash.csv", clash, withRefs))
                .hasMessageContaining("bank reference repeats");
        assertThat(count(jdbc, "statement_file", account)).isZero();
        assertThat(count(jdbc, "transaction", account)).isZero();
    }

    @Test
    void descriptionNormIsTheHashNormalization() throws Exception {
        load(JAN_MAR);

        assertThat(jdbc.queryForObject("""
                SELECT description_norm FROM transaction
                WHERE account_id = ? AND description_raw LIKE 'CUMPARARE POS STARBUCKS%' LIMIT 1""", String.class, account))
                .isEqualTo("CUMPARARE POS STARBUCKS AFI COTROCENI CARD");
    }
}
