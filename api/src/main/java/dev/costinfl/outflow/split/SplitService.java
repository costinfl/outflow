package dev.costinfl.outflow.split;

import dev.costinfl.outflow.split.SplitCalculator.Allocation;
import dev.costinfl.outflow.split.SplitCalculator.Mode;
import dev.costinfl.outflow.split.SplitCalculator.PersonInput;
import dev.costinfl.outflow.split.SplitCalculator.PersonShare;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared expenses (CP6.21): the user marks money out as split with other people, and every figure then counts only the
 * user's own share ({@code transaction.others_share_minor}, read through {@code txn.Scope.MINE}). A split is the user's
 * decision: nothing automatic writes or removes it.
 */
@Service
public class SplitService {

    static final long HOUSEHOLD = 1;

    private final JdbcTemplate jdbc;

    public SplitService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A split as the UI shows it: the expense, the user's part, and each person's part. */
    public record SplitView(long transactionId, LocalDate bookingDate, String merchantName, String currency,
            long amountMinor, Mode mode, Integer myShares, long mineMinor, long othersMinor, List<PersonShare> people) {}

    /** Thrown when the transaction cannot be split (money in, still pending). */
    public static class NotSplittableException extends IllegalStateException {
        NotSplittableException(String message) {
            super(message);
        }
    }

    /**
     * Splits (or re-splits) a money-out transaction.
     *
     * @throws NoSuchElementException for an unknown transaction
     * @throws NotSplittableException for money in or a pending row
     * @throws IllegalArgumentException for a split that does not add up (see {@link SplitCalculator})
     */
    @Transactional
    public SplitView save(long transactionId, Mode mode, Integer myShares, List<PersonInput> people) {
        var tx = jdbc.query("SELECT amount_minor, status, superseded_by FROM transaction WHERE id = ? AND household_id = ?",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getObject(3)}, transactionId, HOUSEHOLD)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("No transaction " + transactionId));
        long amount = (long) tx[0];
        if (amount >= 0) {
            throw new NotSplittableException("Only money out can be split");
        }
        if ("PENDING".equals(tx[1]) || tx[2] != null) {
            throw new NotSplittableException("This payment is still pending; split it once it is posted");
        }
        Allocation allocation = SplitCalculator.allocate(-amount, mode, myShares, people);
        Integer storedMyShares = mode == Mode.SHARES ? (myShares == null ? 1 : myShares) : null;
        long splitId = jdbc.queryForObject("""
                INSERT INTO expense_split (household_id, transaction_id, mode, my_shares) VALUES (?, ?, ?, ?)
                ON CONFLICT (transaction_id) DO UPDATE
                    SET mode = EXCLUDED.mode, my_shares = EXCLUDED.my_shares, updated_at = now()
                RETURNING id""", Long.class, HOUSEHOLD, transactionId, mode.name(), storedMyShares);
        // People are kept by name (any case), so what they already paid back stays theirs (CP6.22).
        record Existing(long id, String name, long repaid) {}
        var existing = new HashMap<String, Existing>();
        jdbc.query("""
                SELECT p.id, p.name, coalesce((SELECT sum(amount_minor) FROM split_settlement s WHERE s.split_person_id = p.id), 0)
                FROM expense_split_person p WHERE p.split_id = ?""", rs -> {
            existing.put(rs.getString(2).toLowerCase(java.util.Locale.ROOT),
                    new Existing(rs.getLong(1), rs.getString(2), rs.getLong(3)));
        }, splitId);
        var kept = new java.util.HashSet<Long>();
        for (PersonShare p : allocation.people()) {
            Existing e = existing.get(p.name().toLowerCase(java.util.Locale.ROOT));
            if (e != null && p.amountMinor() < e.repaid()) {
                throw new NotSplittableException(p.name() + " already paid back more than this; undo that repayment first");
            }
            if (e != null) {
                kept.add(e.id());
            }
        }
        for (Existing e : existing.values()) {
            if (!kept.contains(e.id()) && e.repaid() > 0) {
                throw new NotSplittableException(e.name() + " already paid back; undo that repayment first");
            }
        }
        jdbc.update("UPDATE expense_split_person SET position = -position - 1 WHERE split_id = ?", splitId);
        for (Existing e : existing.values()) {
            if (!kept.contains(e.id())) {
                jdbc.update("DELETE FROM expense_split_person WHERE id = ?", e.id());
            }
        }
        int position = 0;
        for (PersonShare p : allocation.people()) {
            Existing e = existing.get(p.name().toLowerCase(java.util.Locale.ROOT));
            if (e != null) {
                jdbc.update("""
                        UPDATE expense_split_person SET position = ?, name = ?, percent = ?, fixed_minor = ?, shares = ?,
                            amount_minor = ? WHERE id = ?""",
                        position++, p.name(), p.percent(), p.fixedMinor(), p.shares(), p.amountMinor(), e.id());
            } else {
                jdbc.update("""
                        INSERT INTO expense_split_person (split_id, position, name, percent, fixed_minor, shares, amount_minor)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                        splitId, position++, p.name(), p.percent(), p.fixedMinor(), p.shares(), p.amountMinor());
            }
        }
        jdbc.update("UPDATE transaction SET others_share_minor = ? WHERE id = ?", allocation.othersMinor(), transactionId);
        return find(transactionId).orElseThrow();
    }

    /**
     * Undoes a split: the whole amount is the user's again. False when it was not split.
     *
     * @throws NotSplittableException when someone already paid part of it back (undo that first)
     */
    @Transactional
    public boolean delete(long transactionId) {
        Long repaid = jdbc.queryForObject("""
                SELECT count(*) FROM split_settlement s JOIN expense_split_person p ON p.id = s.split_person_id
                JOIN expense_split e ON e.id = p.split_id WHERE e.transaction_id = ?""", Long.class, transactionId);
        if (repaid != null && repaid > 0) {
            throw new NotSplittableException("Someone already paid part of this back; undo that repayment first");
        }
        int removed = jdbc.update("DELETE FROM expense_split WHERE transaction_id = ? AND household_id = ?", transactionId,
                HOUSEHOLD);
        jdbc.update("UPDATE transaction SET others_share_minor = 0 WHERE id = ?", transactionId);
        return removed > 0;
    }

    public Optional<SplitView> find(long transactionId) {
        return query(" AND s.transaction_id = ?", transactionId).stream().findFirst();
    }

    /** Every split, newest expense first. */
    public List<SplitView> list() {
        return query("", new Object[0]);
    }

    /** Names used in splits so far, the most used first: suggestions for the next one. */
    public List<String> people() {
        return jdbc.queryForList("""
                SELECT p.name FROM expense_split_person p JOIN expense_split s ON s.id = p.split_id
                WHERE s.household_id = ? GROUP BY p.name ORDER BY count(*) DESC, p.name""", String.class, HOUSEHOLD);
    }

    private List<SplitView> query(String where, Object... args) {
        var params = new ArrayList<Object>();
        params.add(HOUSEHOLD);
        params.addAll(List.of(args));
        record Head(long splitId, long transactionId, LocalDate date, String merchant, String currency, long amount,
                Mode mode, Integer myShares, long others) {}
        var heads = jdbc.query("""
                SELECT s.id, t.id, t.booking_date, m.display_name, t.currency, -t.amount_minor, s.mode, s.my_shares,
                       t.others_share_minor
                FROM expense_split s JOIN transaction t ON t.id = s.transaction_id
                LEFT JOIN merchant m ON m.id = t.merchant_id
                WHERE s.household_id = ?""" + where + " ORDER BY t.booking_date DESC, t.id DESC",
                (rs, i) -> new Head(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate.class), rs.getString(4),
                        rs.getString(5), rs.getLong(6), Mode.valueOf(rs.getString(7)), (Integer) rs.getObject(8),
                        rs.getLong(9)),
                params.toArray());
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PersonShare>> people = new HashMap<>();
        jdbc.query("""
                SELECT split_id, name, percent, fixed_minor, shares, amount_minor FROM expense_split_person
                WHERE split_id = ANY (?) ORDER BY split_id, position""", rs -> {
            var pct = rs.getBigDecimal(3);
            people.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new PersonShare(rs.getString(2),
                    pct == null ? null : pct.stripTrailingZeros(), (Long) rs.getObject(4), (Integer) rs.getObject(5),
                    rs.getLong(6)));
        }, (Object) heads.stream().map(Head::splitId).toArray(Long[]::new));
        return heads.stream().map(h -> new SplitView(h.transactionId(), h.date(), h.merchant(), h.currency(), h.amount(),
                h.mode(), h.myShares(), h.amount() - h.others(), h.others(),
                people.getOrDefault(h.splitId(), List.of()))).toList();
    }
}
