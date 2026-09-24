package dev.costinfl.outflow.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared helpers for DB-backed import tests. */
public final class ImportFixtures {

    public static final Path SAMPLES = Path.of("..", "samples", "synthetic");

    private ImportFixtures() {}

    public static byte[] sample(String file) throws IOException {
        return Files.readAllBytes(SAMPLES.resolve(file));
    }

    /** Header + data lines of a generic-format sample. */
    public static List<String> lines(String file) throws IOException {
        return Files.readAllLines(SAMPLES.resolve(file)).stream().filter(l -> !l.isBlank()).toList();
    }

    public static void reset(JdbcTemplate jdbc) {
        // TRUNCATE does not fire row-level triggers: the only way to clear immutable raw rows.
        jdbc.execute("TRUNCATE transaction_source, transaction, raw_row, statement_file, account, merchant "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM merchant_alias WHERE source = 'USER'");
        jdbc.update("DELETE FROM category_rule WHERE source = 'USER'");
    }

    public static long newAccount(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, currency, kind)
                VALUES (1, 1, ?, 'RON', 'CURRENT') RETURNING id""", Long.class, name);
    }

    public static long count(JdbcTemplate jdbc, String table, long accountId) {
        String where = switch (table) {
            case "transaction", "statement_file" -> " WHERE account_id = ?";
            case "raw_row" -> " WHERE statement_file_id IN (SELECT id FROM statement_file WHERE account_id = ?)";
            case "transaction_source" -> " WHERE transaction_id IN (SELECT id FROM transaction WHERE account_id = ?)";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, accountId);
    }

    public static List<String> identityKeys(JdbcTemplate jdbc, long accountId) {
        return jdbc.queryForList(
                "SELECT identity_key FROM transaction WHERE account_id = ? ORDER BY identity_key", String.class, accountId);
    }
}
