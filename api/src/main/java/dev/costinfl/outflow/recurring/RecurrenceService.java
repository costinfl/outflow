package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.RecurrenceDetector.Group;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs the detector over every account's outgoing charges (DESIGN: Recurrence detection, step 1), and over its income.
 * Transfers, cash withdrawals and refunds are never subscriptions, so they are left out before grouping. Income is
 * money received in an INCOME category: uncategorized money in is not income until the user says so.
 */
@Service
public class RecurrenceService {

    private final JdbcTemplate jdbc;
    private final RecurrenceDetector detector = new RecurrenceDetector();

    public RecurrenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Candidates across all accounts, payments and income, strongest first. */
    public List<Candidate> detect(LocalDate today) {
        record Key(long accountId, long merchantId, String currency, Direction direction) {}
        var groups = new LinkedHashMap<Key, List<Occurrence>>();
        jdbc.query("""
                SELECT t.account_id, t.merchant_id, t.currency, t.id, t.booking_date, abs(t.amount_minor),
                       CASE WHEN t.amount_minor < 0 THEN 'OUT' ELSE 'IN' END
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.merchant_id IS NOT NULL AND t.transfer_state IS NULL AND t.superseded_by IS NULL
                  AND ((t.amount_minor < 0 AND (c.id IS NULL OR (c.kind <> 'TRANSFER' AND c.code <> 'CASH')))
                    OR (t.amount_minor > 0 AND c.kind = 'INCOME'))
                ORDER BY 7 DESC, t.account_id, t.merchant_id, t.currency, t.booking_date, t.id""", rs -> {
            var key = new Key(rs.getLong(1), rs.getLong(2), rs.getString(3), Direction.valueOf(rs.getString(7)));
            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Occurrence(rs.getLong(4), rs.getObject(5, LocalDate.class), rs.getLong(6)));
        });
        var candidates = new ArrayList<Candidate>();
        groups.forEach((key, occurrences) -> candidates.addAll(detector.detect(
                new Group(key.accountId(), key.merchantId(), key.currency(), occurrences, key.direction()), today)));
        candidates.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return candidates;
    }
}
