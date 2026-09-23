package dev.costinfl.outflow.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.costinfl.outflow.ingest.identity.IdentityKeys;
import dev.costinfl.outflow.ingest.identity.IdentityKeys.IdentifiedRow;
import dev.costinfl.outflow.ingest.parse.ParsedStatement;
import dev.costinfl.outflow.ingest.parse.StatementParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pipeline stages A–F for one file (DESIGN: Processing pipeline), in one DB transaction: file hash → parse →
 * immutable raw rows → identity keys → transaction upsert + source links. Never updates or deletes a transaction.
 */
@Service
public class ImportService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ImportService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ImportResult importFile(long accountId, String fileName, byte[] content, StatementParser parser) {
        byte[] sha256 = sha256(content);
        var householdId = jdbc.queryForObject("SELECT household_id FROM account WHERE id = ?", Long.class, accountId);

        // Parse before writing anything: an unreadable file stores nothing.
        ParsedStatement parsed;
        try {
            parsed = parser.parse(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Exact re-upload for this account is a no-op. ON CONFLICT also covers a concurrent upload of the same file.
        Optional<Long> fileId = jdbc.query("""
                        INSERT INTO statement_file (account_id, sha256, format, file_name, period_from, period_to, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'IMPORTED')
                        ON CONFLICT ON CONSTRAINT statement_file_account_sha256_uq DO NOTHING
                        RETURNING id""",
                (rs, i) -> rs.getLong(1),
                accountId, sha256, parser.id(), fileName,
                parsed.periodFrom().map(Date::valueOf).orElse(null),
                parsed.periodTo().map(Date::valueOf).orElse(null)).stream().findFirst();
        if (fileId.isEmpty()) {
            return new ImportResult(accountId, Optional.empty(), parser.id(), true, parsed.rows().size(), 0,
                    parsed.rows().size(), parsed.periodFrom(), parsed.periodTo());
        }

        List<IdentifiedRow> identified = IdentityKeys.assign(accountId, parsed.rows());
        int created = 0;
        for (IdentifiedRow r : identified) {
            long rawRowId = jdbc.queryForObject(
                    "INSERT INTO raw_row (statement_file_id, row_no, payload) VALUES (?, ?, ?::jsonb) RETURNING id",
                    Long.class, fileId.get(), r.row().rowNo(), toJson(r));
            Optional<Long> newId = jdbc.query("""
                            INSERT INTO transaction (household_id, account_id, identity_key, booking_date, value_date,
                                                     amount_minor, currency, description_raw, description_norm, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED')
                            ON CONFLICT ON CONSTRAINT transaction_account_identity_uq DO NOTHING
                            RETURNING id""",
                    (rs, i) -> rs.getLong(1),
                    householdId, accountId, r.identityKey(), Date.valueOf(r.row().bookingDate()),
                    r.row().valueDate().map(Date::valueOf).orElse(null), r.row().amountMinor(), r.row().currency(),
                    r.row().description(), r.descriptionNorm()).stream().findFirst();
            long transactionId = newId.orElseGet(() -> jdbc.queryForObject(
                    "SELECT id FROM transaction WHERE account_id = ? AND identity_key = ?",
                    Long.class, accountId, r.identityKey()));
            if (newId.isPresent()) {
                created++;
            }
            jdbc.update("INSERT INTO transaction_source (transaction_id, raw_row_id) VALUES (?, ?)",
                    transactionId, rawRowId);
        }
        return new ImportResult(accountId, fileId, parser.id(), false, identified.size(), created,
                identified.size() - created, parsed.periodFrom(), parsed.periodTo());
    }

    private String toJson(IdentifiedRow r) {
        try {
            return json.writeValueAsString(r.row().payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
