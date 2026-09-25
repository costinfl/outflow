package dev.costinfl.outflow.txn;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pending vs posted (DESIGN: Transaction identity, "Pending vs posted"): a pending payment that reappears posted with a
 * shifted date or a final amount has another identity key, so it would count twice. A pending row and a posted row
 * match when they share account, merchant and currency, their amounts differ by at most 5% or 2 currency units, and
 * their dates by at most {@value #MAX_DAYS} days.
 *
 * <p>A single unambiguous match (each side has only the other) links automatically: the pending row is superseded, not
 * deleted, and counts nowhere. Several candidates become review questions. AUTO links are recomputed on every run, so
 * upload order does not matter; the user's answers (SAME, DIFFERENT) are kept for good.
 */
@Service
public class SoftMatchService {

    static final long HOUSEHOLD = 1;
    static final int MAX_DAYS = 5;

    private final JdbcTemplate jdbc;

    public SoftMatchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(int linked, int questions) {}

    record Row(long id, long accountId, long merchantId, String currency, LocalDate date, long amountMinor) {}

    @Transactional
    public Result matchAll() {
        // Pending rows the automation may decide about (not linked by the user), and posted rows not claimed by a USER
        // link. Pending rows linked by the user keep their link whatever happens.
        List<Row> pending = jdbc.query("""
                SELECT id, account_id, merchant_id, currency, booking_date, amount_minor FROM transaction
                WHERE household_id = ? AND status = 'PENDING' AND merchant_id IS NOT NULL
                  AND superseded_source IS DISTINCT FROM 'USER'""", this::row, HOUSEHOLD);
        List<Row> posted = jdbc.query("""
                SELECT id, account_id, merchant_id, currency, booking_date, amount_minor FROM transaction t
                WHERE household_id = ? AND status = 'POSTED' AND merchant_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM transaction p WHERE p.superseded_by = t.id AND p.superseded_source = 'USER')""",
                this::row, HOUSEHOLD);
        Set<List<Long>> different = new HashSet<>(jdbc.query("""
                SELECT pending_transaction_id, posted_transaction_id FROM soft_match_review WHERE resolution = 'DIFFERENT'""",
                (rs, i) -> List.of(rs.getLong(1), rs.getLong(2))));

        record Key(long accountId, long merchantId, String currency) {}
        var postedByKey = new HashMap<Key, List<Row>>();
        posted.forEach(q -> postedByKey.computeIfAbsent(new Key(q.accountId(), q.merchantId(), q.currency()),
                k -> new ArrayList<>()).add(q));
        var candidates = new HashMap<Long, List<Row>>(); // pending id → posted candidates
        var degree = new HashMap<Long, Integer>(); // posted id → number of pending candidates
        for (Row p : pending) {
            for (Row q : postedByKey.getOrDefault(new Key(p.accountId(), p.merchantId(), p.currency()), List.of())) {
                if (matches(p, q) && !different.contains(List.of(p.id(), q.id()))) {
                    candidates.computeIfAbsent(p.id(), k -> new ArrayList<>()).add(q);
                    degree.merge(q.id(), 1, Integer::sum);
                }
            }
        }

        var current = new HashMap<Long, Long>();
        jdbc.query("SELECT id, superseded_by FROM transaction WHERE superseded_source = 'AUTO'", rs -> {
            current.put(rs.getLong(1), rs.getLong(2));
        });
        int linked = 0, questions = 0;
        for (Row p : pending) {
            List<Row> qs = candidates.getOrDefault(p.id(), List.of());
            Long wanted = qs.size() == 1 && degree.get(qs.getFirst().id()) == 1 ? qs.getFirst().id() : null;
            Long now = current.get(p.id());
            if (wanted != null && !wanted.equals(now)) {
                jdbc.update("UPDATE transaction SET superseded_by = ?, superseded_source = 'AUTO' WHERE id = ?", wanted, p.id());
                linked++;
            } else if (wanted == null && now != null) {
                jdbc.update("UPDATE transaction SET superseded_by = NULL, superseded_source = NULL WHERE id = ?", p.id());
            }
            if (wanted == null) {
                for (Row q : qs) {
                    questions += jdbc.update("""
                            INSERT INTO soft_match_review (household_id, pending_transaction_id, posted_transaction_id, reason)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT ON CONSTRAINT soft_match_review_pair_uq DO NOTHING""",
                            HOUSEHOLD, p.id(), q.id(), qs.size() > 1 ? "several posted candidates"
                                    : "the posted row matches several pending ones");
                }
            }
        }
        return new Result(linked, questions);
    }

    /** Same sign; amounts within 5% of the pending amount or 2 currency units; dates within {@value #MAX_DAYS} days. */
    static boolean matches(Row pending, Row posted) {
        if (Long.signum(pending.amountMinor()) != Long.signum(posted.amountMinor())) {
            return false;
        }
        if (Math.abs(ChronoUnit.DAYS.between(pending.date(), posted.date())) > MAX_DAYS) {
            return false;
        }
        long diff = Math.abs(pending.amountMinor() - posted.amountMinor());
        return 20 * diff <= Math.abs(pending.amountMinor()) || diff <= twoUnits(pending.currency());
    }

    /** 2 currency units in minor units: 200 for RON or EUR, 2 for JPY. */
    static long twoUnits(String currency) {
        int digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        return 2 * (long) Math.pow(10, digits);
    }

    /** An open question: both rows still undecided (neither side linked to something else since). */
    public record Question(long id, long pendingId, long postedId) {}

    public List<Question> openQuestions() {
        return jdbc.query("""
                SELECT r.id, r.pending_transaction_id, r.posted_transaction_id
                FROM soft_match_review r JOIN transaction p ON p.id = r.pending_transaction_id
                WHERE r.resolution IS NULL AND p.superseded_by IS NULL AND p.status = 'PENDING'
                  AND NOT EXISTS (SELECT 1 FROM transaction x WHERE x.superseded_by = r.posted_transaction_id)
                ORDER BY r.id""", (rs, i) -> new Question(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    /** Thrown when a question was already answered. */
    public static class AnsweredException extends IllegalStateException {
        AnsweredException(String message) {
            super(message);
        }
    }

    /**
     * The user's answer. SAME links the pair for good (USER); DIFFERENT means these two never match. Then the automatic
     * pass runs again, since an answer can leave another match unambiguous.
     */
    @Transactional
    public void answer(long questionId, boolean same) {
        Map<String, Object> q = jdbc.queryForList(
                "SELECT pending_transaction_id, posted_transaction_id, resolution FROM soft_match_review WHERE id = ?",
                questionId).stream().findFirst().orElseThrow(() -> new NoSuchElementException("No question " + questionId));
        if (q.get("resolution") != null) {
            throw new AnsweredException("Question " + questionId + " was answered: " + q.get("resolution"));
        }
        long pendingId = ((Number) q.get("pending_transaction_id")).longValue();
        long postedId = ((Number) q.get("posted_transaction_id")).longValue();
        if (same) {
            // A posted row replaces one pending row: an AUTO link to it from elsewhere gives way.
            jdbc.update("UPDATE transaction SET superseded_by = NULL, superseded_source = NULL WHERE superseded_by = ? AND superseded_source = 'AUTO'",
                    postedId);
            jdbc.update("UPDATE transaction SET superseded_by = ?, superseded_source = 'USER' WHERE id = ?", postedId, pendingId);
        }
        jdbc.update("UPDATE soft_match_review SET resolution = ?, resolved_at = now() WHERE id = ?",
                same ? "SAME" : "DIFFERENT", questionId);
        matchAll();
    }

    private Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getObject(5, LocalDate.class),
                rs.getLong(6));
    }
}
