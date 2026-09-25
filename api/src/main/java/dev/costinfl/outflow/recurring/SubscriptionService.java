package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.Subscription.Edits;
import dev.costinfl.outflow.recurring.Subscription.EndedBy;
import dev.costinfl.outflow.recurring.Subscription.State;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code subscription} in step with the detector and applies the user's decisions
 * (DESIGN: Subscription candidate lifecycle). The system never promotes a candidate on its own, never changes what the
 * user confirmed, and never re-proposes something rejected.
 */
@Service
public class SubscriptionService {

    static final long HOUSEHOLD = 1;

    private final JdbcTemplate jdbc;
    private final RecurrenceService recurrence;
    private final Clock clock;

    public SubscriptionService(JdbcTemplate jdbc, RecurrenceService recurrence, Clock clock) {
        this.jdbc = jdbc;
        this.recurrence = recurrence;
        this.clock = clock;
    }

    /** {@link #refresh(LocalDate)} as of today: after an upload, a category change or a merchant alias. */
    @Transactional
    public RefreshResult refreshNow() {
        return refresh(LocalDate.now(clock));
    }

    public record RefreshResult(int proposed, int updated, int dropped, int linked) {}

    /**
     * Runs the detector and reconciles its candidates with the stored rows (pipeline, after categories):
     * <ul>
     *   <li>no stored stream → a new PROPOSED row, unless a rejection covers it;</li>
     *   <li>PROPOSED → detected fields refreshed;</li>
     *   <li>CONFIRMED → new charges linked, last seen and next expected moved on; the user's fields stay;</li>
     *   <li>ENDED by the system → CONFIRMED again when a newer charge arrives; ended by the user → left alone;</li>
     *   <li>PROPOSED rows the detector no longer finds are dropped.</li>
     * </ul>
     */
    @Transactional
    public RefreshResult refresh(LocalDate today) {
        // A pending charge replaced by its posted version is no longer one of a subscription's charges.
        jdbc.update("UPDATE transaction SET subscription_id = NULL WHERE superseded_by IS NOT NULL AND subscription_id IS NOT NULL");
        List<Subscription> live = new ArrayList<>(jdbc.query(
                SELECT + " WHERE state <> 'REJECTED' ORDER BY id", this::row));
        var seen = new HashSet<Long>();
        int proposed = 0, updated = 0, linked = 0;
        for (Candidate c : recurrence.detect(today)) {
            Optional<Subscription> match = live.stream()
                    .filter(s -> !seen.contains(s.id()) && sameStream(s, c)).findFirst();
            if (match.isEmpty()) {
                if (rejected(c)) {
                    continue;
                }
                long id = insert(c);
                seen.add(id);
                linked += link(id, c.transactionIds());
                proposed++;
                continue;
            }
            Subscription s = match.get();
            seen.add(s.id());
            switch (s.state()) {
                case PROPOSED -> {
                    updateDetected(s.id(), c);
                    jdbc.update("UPDATE transaction SET subscription_id = NULL WHERE subscription_id = ? AND NOT id = ANY (?)",
                            s.id(), ids(c.transactionIds()));
                    linked += link(s.id(), c.transactionIds());
                    updated++;
                }
                case CONFIRMED -> {
                    linked += link(s.id(), c.transactionIds());
                    moveOn(s, c);
                    updated++;
                }
                case ENDED -> {
                    if (s.endedBy() == EndedBy.SYSTEM && c.lastDate().isAfter(s.lastSeen())) {
                        jdbc.update("UPDATE subscription SET state = 'CONFIRMED', ended_by = NULL, updated_at = now() WHERE id = ?",
                                s.id());
                        linked += link(s.id(), c.transactionIds());
                        moveOn(s, c);
                        updated++;
                    }
                }
                case REJECTED -> throw new IllegalStateException("rejected rows are not matched");
            }
        }
        int dropped = 0;
        for (Subscription s : live) {
            if (s.state() == State.PROPOSED && !seen.contains(s.id())) {
                jdbc.update("UPDATE transaction SET subscription_id = NULL WHERE subscription_id = ?", s.id());
                jdbc.update("DELETE FROM subscription WHERE id = ?", s.id());
                dropped++;
            }
        }
        return new RefreshResult(proposed, updated, dropped, linked);
    }

    /** PROPOSED or ENDED → CONFIRMED, with the user's edits. */
    @Transactional
    public Subscription confirm(long id, Edits edits) {
        Subscription s = require(id, State.PROPOSED, State.ENDED);
        jdbc.update("""
                UPDATE subscription SET state = 'CONFIRMED', ended_by = NULL, name = ?, cadence = ?,
                    expected_amount_minor = ?, decided_at = now(), updated_at = now()
                WHERE id = ?""",
                edits.name() != null && !edits.name().isBlank() ? edits.name().strip() : s.name(),
                (edits.cadence() != null ? edits.cadence() : s.cadence()).name(),
                edits.expectedAmountMinor() != null ? edits.expectedAmountMinor() : s.expectedAmountMinor(),
                id);
        return find(id).orElseThrow();
    }

    /** PROPOSED → REJECTED: its charges are unlinked and the (merchant, cadence, amount) is never proposed again. */
    @Transactional
    public Subscription reject(long id) {
        Subscription s = require(id, State.PROPOSED);
        jdbc.update("""
                INSERT INTO subscription_rejection (household_id, merchant_id, currency, cadence, amount_minor, subscription_id)
                VALUES (?, ?, ?, ?, ?, ?)""",
                HOUSEHOLD, s.merchantId(), s.currency(), s.cadence().name(), s.expectedAmountMinor(), id);
        jdbc.update("UPDATE transaction SET subscription_id = NULL WHERE subscription_id = ?", id);
        jdbc.update("UPDATE subscription SET state = 'REJECTED', decided_at = now(), updated_at = now() WHERE id = ?", id);
        return find(id).orElseThrow();
    }

    /** CONFIRMED → ENDED by the user: it stays ended whatever is imported later. Its past charges stay linked. */
    @Transactional
    public Subscription end(long id) {
        require(id, State.CONFIRMED);
        jdbc.update("""
                UPDATE subscription SET state = 'ENDED', ended_by = 'USER', next_expected_date = NULL,
                    decided_at = now(), updated_at = now()
                WHERE id = ?""", id);
        return find(id).orElseThrow();
    }

    /** Rename a confirmed or ended subscription (the name is the user's; refresh never changes it). */
    @Transactional
    public Subscription rename(long id, String name) {
        require(id, State.CONFIRMED, State.ENDED);
        jdbc.update("UPDATE subscription SET name = ?, updated_at = now() WHERE id = ?", name.strip(), id);
        return find(id).orElseThrow();
    }

    public Optional<Subscription> find(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::row, id).stream().findFirst();
    }

    public List<Subscription> list() {
        return jdbc.query(SELECT + " ORDER BY id", this::row);
    }

    /** Thrown when a transition is not allowed from the row's current state. */
    public static class TransitionException extends IllegalStateException {
        TransitionException(String message) {
            super(message);
        }
    }

    private Subscription require(long id, State... allowed) {
        Subscription s = find(id).orElseThrow(() -> new NoSuchElementException("No subscription " + id));
        for (State state : allowed) {
            if (s.state() == state) {
                return s;
            }
        }
        throw new TransitionException("Subscription " + id + " is " + s.state());
    }

    /**
     * Same account, merchant and currency, and amount bands that overlap once each is allowed 25% upwards (the band
     * split threshold). Cadence is not compared: the user may have corrected it when confirming.
     */
    static boolean sameStream(Subscription s, Candidate c) {
        return s.accountId() == c.accountId() && s.merchantId() == c.merchantId() && s.currency().equals(c.currency())
                && c.bandMinMinor() * 4 <= s.bandMaxMinor() * 5 && s.bandMinMinor() * 4 <= c.bandMaxMinor() * 5;
    }

    /** A rejection covers the candidate unless its cadence differs or its amount moved more than 50%. */
    private boolean rejected(Candidate c) {
        List<Long> amounts = jdbc.queryForList("""
                SELECT amount_minor FROM subscription_rejection
                WHERE household_id = ? AND merchant_id = ? AND currency = ? AND cadence = ?""",
                Long.class, HOUSEHOLD, c.merchantId(), c.currency(), c.cadence().name());
        return amounts.stream().anyMatch(a -> 2 * Math.abs(c.expectedAmountMinor() - a) <= a);
    }

    private long insert(Candidate c) {
        return jdbc.queryForObject("""
                INSERT INTO subscription (household_id, account_id, merchant_id, name, currency, cadence, anchor_day,
                    anchor_month, amount_kind, expected_amount_minor, tolerance_minor, band_min_minor, band_max_minor,
                    confidence, first_seen, last_seen, next_expected_date, state)
                SELECT ?, ?, m.id, m.display_name, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED'
                FROM merchant m WHERE m.id = ?
                RETURNING id""", Long.class,
                HOUSEHOLD, c.accountId(), c.currency(), c.cadence().name(), c.anchorDay(), c.anchorMonth(),
                c.amountKind().name(), c.expectedAmountMinor(), c.toleranceMinor(), c.bandMinMinor(), c.bandMaxMinor(),
                confidence(c), c.firstDate(), c.lastDate(), c.nextExpectedDate(), c.merchantId());
    }

    private void updateDetected(long id, Candidate c) {
        jdbc.update("""
                UPDATE subscription SET cadence = ?, anchor_day = ?, anchor_month = ?, amount_kind = ?,
                    expected_amount_minor = ?, tolerance_minor = ?, band_min_minor = ?, band_max_minor = ?, confidence = ?,
                    first_seen = ?, last_seen = ?, next_expected_date = ?, updated_at = now()
                WHERE id = ?""",
                c.cadence().name(), c.anchorDay(), c.anchorMonth(), c.amountKind().name(), c.expectedAmountMinor(),
                c.toleranceMinor(), c.bandMinMinor(), c.bandMaxMinor(), confidence(c), c.firstDate(), c.lastDate(),
                c.nextExpectedDate(), id);
    }

    /** A confirmed stream's newest charge: only the dates move; name, cadence and amount are the user's. */
    private void moveOn(Subscription s, Candidate c) {
        if (c.lastDate().isAfter(s.lastSeen())) {
            jdbc.update("UPDATE subscription SET last_seen = ?, next_expected_date = ?, updated_at = now() WHERE id = ?",
                    c.lastDate(), c.nextExpectedDate(), s.id());
        }
    }

    /** Links charges not linked to any subscription yet. Returns how many were newly linked. */
    private int link(long subscriptionId, List<Long> transactionIds) {
        return jdbc.update("UPDATE transaction SET subscription_id = ? WHERE id = ANY (?) AND subscription_id IS NULL",
                subscriptionId, ids(transactionIds));
    }

    private Long[] ids(List<Long> ids) {
        return ids.toArray(Long[]::new);
    }

    private static BigDecimal confidence(Candidate c) {
        return BigDecimal.valueOf(c.confidence()).setScale(3, RoundingMode.HALF_UP);
    }

    private static final String SELECT = """
            SELECT id, account_id, merchant_id, name, currency, cadence, anchor_day, anchor_month, amount_kind,
                   expected_amount_minor, tolerance_minor, band_min_minor, band_max_minor, confidence, first_seen,
                   last_seen, next_expected_date, state, ended_by
            FROM subscription""";

    private Subscription row(ResultSet rs, int i) throws SQLException {
        String endedBy = rs.getString("ended_by");
        return new Subscription(rs.getLong("id"), rs.getLong("account_id"), rs.getLong("merchant_id"),
                rs.getString("name"), rs.getString("currency"), Cadence.valueOf(rs.getString("cadence")),
                rs.getObject("anchor_day", Integer.class), rs.getObject("anchor_month", Integer.class),
                AmountKind.valueOf(rs.getString("amount_kind")), rs.getLong("expected_amount_minor"),
                rs.getLong("tolerance_minor"), rs.getLong("band_min_minor"), rs.getLong("band_max_minor"),
                rs.getBigDecimal("confidence"), rs.getObject("first_seen", LocalDate.class),
                rs.getObject("last_seen", LocalDate.class), rs.getObject("next_expected_date", LocalDate.class),
                State.valueOf(rs.getString("state")), endedBy == null ? null : EndedBy.valueOf(endedBy));
    }
}
