package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.RecurrenceDetector.Group;
import dev.costinfl.outflow.txn.Scope;
import dev.costinfl.outflow.txn.Slice;
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

    /**
     * Recurring transfers out (own-account pairs and the Transfer category), from transactions booked up to
     * {@code asOf} in one currency and the selected accounts, strongest first. Each comes with the own account most of
     * its transfers went to, when they are paired.
     */
    public List<TransferStream> detectTransfers(LocalDate asOf, Slice slice) {
        record Key(long accountId, long merchantId) {}
        var groups = new LinkedHashMap<Key, List<Occurrence>>();
        var destination = new java.util.HashMap<Long, Long>();
        jdbc.query("""
                SELECT t.account_id, t.merchant_id, t.id, t.booking_date, -t.amount_minor, t.transfer_account_id
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.amount_minor < 0 AND t.merchant_id IS NOT NULL AND t.superseded_by IS NULL
                  AND t.booking_date <= ? AND (t.transfer_state IS NOT NULL OR c.kind = 'TRANSFER')
                  AND""" + " " + Scope.SLICE + """

                ORDER BY t.account_id, t.merchant_id, t.booking_date, t.id""", rs -> {
            groups.computeIfAbsent(new Key(rs.getLong(1), rs.getLong(2)), k -> new ArrayList<>())
                    .add(new Occurrence(rs.getLong(3), rs.getObject(4, LocalDate.class), rs.getLong(5)));
            if (rs.getObject(6) != null) {
                destination.put(rs.getLong(3), rs.getLong(6));
            }
        }, slice.args(asOf));
        var streams = new ArrayList<TransferStream>();
        groups.forEach((key, occurrences) -> {
            for (Candidate c : detector.detect(new Group(key.accountId(), key.merchantId(), slice.currency(), occurrences), asOf)) {
                if (c.strength() != Candidate.Strength.PROPOSED) {
                    continue;
                }
                Long to = c.transactionIds().stream().map(destination::get).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.groupingBy(id -> id, java.util.stream.Collectors.counting()))
                        .entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey)
                        .orElse(null);
                streams.add(new TransferStream(c, to));
            }
        });
        streams.sort((a, b) -> Double.compare(b.candidate().confidence(), a.candidate().confidence()));
        return streams;
    }

    /** A detected standing transfer and the own account it goes to (null when not paired). */
    public record TransferStream(Candidate candidate, Long toAccountId) {}

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
