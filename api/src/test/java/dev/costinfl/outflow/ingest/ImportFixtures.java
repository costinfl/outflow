package dev.costinfl.outflow.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared helpers for DB-backed import tests. */
final class ImportFixtures {

    static final Path SAMPLES = Path.of("..", "samples", "synthetic");

    private ImportFixtures() {}

    static byte[] sample(String file) throws IOException {
        return Files.readAllBytes(SAMPLES.resolve(file));
    }

    /** Header + data lines of a generic-format sample. */
    static List<String> lines(String file) throws IOException {
        return Files.readAllLines(SAMPLES.resolve(file)).stream().filter(l -> !l.isBlank()).toList();
    }

    static void reset(JdbcTemplate jdbc) {
        // TRUNCATE does not fire row-level triggers: the only way to clear immutable raw rows.
        jdbc.execute("TRUNCATE transaction_source, transaction, raw_row, statement_file, account RESTART IDENTITY CASCADE");
    }

    static long newAccount(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO account (household_id, owner_user_id, name, currency, kind)
                VALUES (1, 1, ?, 'RON', 'CURRENT') RETURNING id""", Long.class, name);
    }

    static long count(JdbcTemplate jdbc, String table, long accountId) {
        String where = switch (table) {
            case "transaction", "statement_file" -> " WHERE account_id = ?";
            case "raw_row" -> " WHERE statement_file_id IN (SELECT id FROM statement_file WHERE account_id = ?)";
            case "transaction_source" -> " WHERE transaction_id IN (SELECT id FROM transaction WHERE account_id = ?)";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, accountId);
    }

    static List<String> identityKeys(JdbcTemplate jdbc, long accountId) {
        return jdbc.queryForList(
                "SELECT identity_key FROM transaction WHERE account_id = ? ORDER BY identity_key", String.class, accountId);
    }
}
