package dev.costinfl.outflow.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.costinfl.outflow.TestcontainersConfiguration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** CP1.1: the import schema enforces the non-negotiables at the database level. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ImportSchemaTest {

    private static final byte[] HASH_A = new byte[32];
    private static final byte[] HASH_B = new byte[32];

    static {
        HASH_B[0] = 1;
    }

    @Autowired
    JdbcTemplate jdbc;

    long accountId;

    @BeforeEach
    void cleanAndCreateAccount() {
        // TRUNCATE does not fire row-level triggers, so it is the one way to reset immutable raw rows in tests.
        jdbc.execute("TRUNCATE transaction_source, transaction, raw_row, statement_file, account RESTART IDENTITY CASCADE");
        accountId = insertAccount(HASH_A, "RO49 •••• 1234");
    }

    @Test
    void singleHouseholdAndUserAreSeeded() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE household_id = 1", Long.class)).isEqualTo(1);
    }

    @Test
    void sameFileTwiceForOneAccountIsRejected() {
        insertStatementFile(accountId, HASH_A);

        assertThatThrownBy(() -> insertStatementFile(accountId, HASH_A)).isInstanceOf(DataIntegrityViolationException.class);
        insertStatementFile(accountId, HASH_B); // a different file is fine
    }

    @Test
    void identityKeyIsUniquePerAccount() {
        insertTransaction(accountId, "key_v1:abc#1");
        long other = insertAccount(HASH_B, "RO49 •••• 9031");

        assertThatThrownBy(() -> insertTransaction(accountId, "key_v1:abc#1")).isInstanceOf(DataIntegrityViolationException.class);
        insertTransaction(other, "key_v1:abc#1"); // same key in another account is a different transaction
    }

    @Test
    void rawRowsAreImmutable() {
        long fileId = insertStatementFile(accountId, HASH_A);
        long rowId = insertRawRow(fileId, 1);

        assertThatThrownBy(() -> jdbc.update("UPDATE raw_row SET payload = '{}'::jsonb WHERE id = ?", rowId))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("raw_row is immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM raw_row WHERE id = ?", rowId))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("raw_row is immutable");
    }

    @Test
    void rawRowNumberIsUniquePerFile() {
        long fileId = insertStatementFile(accountId, HASH_A);
        insertRawRow(fileId, 1);

        assertThatThrownBy(() -> insertRawRow(fileId, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRawRowBelongsToAtMostOneTransaction() {
        long fileId = insertStatementFile(accountId, HASH_A);
        long rowId = insertRawRow(fileId, 1);
        long t1 = insertTransaction(accountId, "key_v1:a#1");
        long t2 = insertTransaction(accountId, "key_v1:b#1");
        long secondFile = insertStatementFile(accountId, HASH_B);
        long sameRecordInSecondFile = insertRawRow(secondFile, 7);

        jdbc.update("INSERT INTO transaction_source (transaction_id, raw_row_id) VALUES (?, ?)", t1, rowId);
        // the same record arriving in another file maps to the same transaction
        jdbc.update("INSERT INTO transaction_source (transaction_id, raw_row_id) VALUES (?, ?)", t1, sameRecordInSecondFile);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO transaction_source (transaction_id, raw_row_id) VALUES (?, ?)", t2, rowId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void amountsAreBigintMinorUnits() {
        assertThat(columnType("transaction", "amount_minor")).isEqualTo("bigint");
        long id = insertTransaction(accountId, "key_v1:big#1", 9_000_000_000_000L, "RON");

        assertThat(jdbc.queryForObject("SELECT amount_minor FROM transaction WHERE id = ?", Long.class, id))
                .isEqualTo(9_000_000_000_000L);
    }

    @Test
    void currencyMustBeUppercaseIsoCode() {
        assertThatThrownBy(() -> insertTransaction(accountId, "key_v1:c#1", 100, "ron"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransaction(accountId, "key_v1:c#1", 100, "RONX"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountHasNoPlainIbanColumnAndRejectsUnmaskedIban() {
        assertThat(jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_name = 'account'", String.class))
                .contains("iban_hash", "iban_masked")
                .doesNotContain("iban");

        assertThatThrownBy(() -> insertAccount(HASH_B, "RO49AAAA1B31007593840000"))
                .as("a full IBAN in iban_masked")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAccount(new byte[16], "RO49 •••• 9031"))
                .as("iban_hash must be a 32-byte HMAC-SHA256")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountWithoutIbanIsAllowed() {
        // Account detection may fail; the user then picks or names the account on upload.
        insertAccount(null, null);
    }

    private long insertAccount(byte[] ibanHash, String ibanMasked) {
        return jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, iban_hash, iban_masked, currency, kind)
                VALUES (1, 1, 'Main', ?, ?, 'RON', 'CURRENT') RETURNING id""", Long.class, ibanHash, ibanMasked);
    }

    private long insertStatementFile(long account, byte[] sha256) {
        return jdbc.queryForObject("""
                INSERT INTO statement_file (account_id, sha256, format, status)
                VALUES (?, ?, 'generic-csv-v1', 'IMPORTED') RETURNING id""", Long.class, account, sha256);
    }

    private long insertRawRow(long fileId, int rowNo) {
        return jdbc.queryForObject("""
                INSERT INTO raw_row (statement_file_id, row_no, payload)
                VALUES (?, ?, '{"Description":"LIDL"}'::jsonb) RETURNING id""", Long.class, fileId, rowNo);
    }

    private long insertTransaction(long account, String identityKey) {
        return insertTransaction(account, identityKey, -1999, "RON");
    }

    private long insertTransaction(long account, String identityKey, long amountMinor, String currency) {
        return jdbc.queryForObject("""
                INSERT INTO transaction (household_id, account_id, identity_key, booking_date, amount_minor, currency,
                                         description_raw, description_norm, status)
                VALUES (1, ?, ?, ?, ?, ?, 'LIDL 123 BUCURESTI', 'LIDL 123 BUCURESTI', 'POSTED') RETURNING id""",
                Long.class, account, identityKey, LocalDate.of(2026, 3, 1), amountMinor, currency);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}
