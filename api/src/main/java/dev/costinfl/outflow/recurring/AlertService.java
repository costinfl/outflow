package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.RecurrenceDetector.Anchor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prediction and alerts for confirmed subscriptions (DESIGN: Recurrence detection, step 5):
 * <ul>
 *   <li>new charges near the next due date link automatically, even when the price moved out of the detector's band;</li>
 *   <li>a charge outside expected ± tolerance → PRICE_CHANGE question;</li>
 *   <li>no charge by next expected + tolerance + {@value #GRACE_DAYS} days → MISSED question;</li>
 *   <li>two missed in a row → ENDED by the system (it resumes when charges return).</li>
 * </ul>
 */
@Service
public class AlertService {

    /** Days past the cadence tolerance before a charge counts as missed. */
    static final int GRACE_DAYS = 3;

    private final JdbcTemplate jdbc;

    public AlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum Kind { PRICE_CHANGE, MISSED }

    /** Answers on an alert card: PRICE_CHANGE takes GOT_IT or END, MISSED takes STILL_ACTIVE or CANCELLED. */
    public enum Action { GOT_IT, END, STILL_ACTIVE, CANCELLED }

    record Sub(long id, long accountId, long merchantId, String currency, Cadence cadence, Integer anchorDay,
            Integer anchorMonth, long expected, long tolerance, LocalDate lastSeen, LocalDate next,
            LocalDate confirmedThrough, String state) {

        Anchor anchor() {
            return new Anchor(cadence, anchorMonth == null ? 0 : anchorMonth, anchorDay == null ? 0 : anchorDay);
        }

        /** The last day a charge for {@code due} can arrive before it counts as missed. */
        LocalDate deadline(LocalDate due) {
            return due.plusDays(cadence.toleranceDays + GRACE_DAYS);
        }
    }

    /** Runs the checks for every confirmed (and system-ended) subscription. Returns how many charges it linked. */
    @Transactional
    public int check(LocalDate today) {
        List<Sub> subs = jdbc.query("""
                SELECT id, account_id, merchant_id, currency, cadence, anchor_day, anchor_month, expected_amount_minor,
                       tolerance_minor, last_seen, next_expected_date, confirmed_through, state
                FROM subscription WHERE state = 'CONFIRMED' OR (state = 'ENDED' AND ended_by = 'SYSTEM')""",
                (rs, i) -> new Sub(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        Cadence.valueOf(rs.getString(5)), rs.getObject(6, Integer.class), rs.getObject(7, Integer.class),
                        rs.getLong(8), rs.getLong(9), rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class),
                        rs.getObject(12, LocalDate.class), rs.getString(13)));
        int linked = 0;
        for (Sub s : subs) {
            int n = linkNewCharges(s);
            linked += n;
            if (n > 0 && s.state().equals("ENDED")) {
                // Charges resumed after the system ended it (two missed): active again.
                jdbc.update("UPDATE subscription SET state = 'CONFIRMED', ended_by = NULL, updated_at = now() WHERE id = ?",
                        s.id());
            } else if (s.state().equals("ENDED")) {
                continue;
            }
            Sub now = reload(s.id());
            checkPrice(now);
            checkMissed(now, today);
        }
        return linked;
    }

    /**
     * Unlinked charges from the same account and merchant after the last one seen, each close to a due date
     * (± tolerance + grace) and within 50% of the expected amount, join the subscription in date order.
     */
    int linkNewCharges(Sub s) {
        var anchor = s.anchor();
        List<Map<String, Object>> charges = jdbc.queryForList("""
                SELECT id, booking_date, -amount_minor AS amount FROM transaction
                WHERE account_id = ? AND merchant_id = ? AND currency = ? AND amount_minor < 0 AND booking_date > ?
                  AND subscription_id IS NULL AND transfer_state IS NULL AND superseded_by IS NULL
                ORDER BY booking_date, id""", s.accountId(), s.merchantId(), s.currency(), s.lastSeen());
        long lastPeriod = anchor.nearestPeriod(s.lastSeen());
        LocalDate lastSeen = s.lastSeen();
        int linked = 0;
        for (Map<String, Object> c : charges) {
            LocalDate date = ((java.sql.Date) c.get("booking_date")).toLocalDate();
            long amount = ((Number) c.get("amount")).longValue();
            long period = anchor.nearestPeriod(date);
            boolean onTime = anchor.distance(date, period) <= s.cadence().toleranceDays + GRACE_DAYS;
            if (period > lastPeriod && onTime && 2 * Math.abs(amount - s.expected()) <= s.expected()) {
                jdbc.update("UPDATE transaction SET subscription_id = ? WHERE id = ?", s.id(), c.get("id"));
                lastPeriod = period;
                lastSeen = date;
                linked++;
            }
        }
        if (linked > 0) {
            jdbc.update("UPDATE subscription SET last_seen = ?, next_expected_date = ?, updated_at = now() WHERE id = ?",
                    lastSeen, anchor.dateIn(lastPeriod + 1), s.id());
            // A charge that arrived late answers the missed question by itself.
            jdbc.update("""
                    UPDATE subscription_alert SET resolution = 'CHARGED', resolved_at = now()
                    WHERE subscription_id = ? AND kind = 'MISSED' AND resolution IS NULL AND due_date <= ?""",
                    s.id(), lastSeen.plusDays(s.cadence().toleranceDays + GRACE_DAYS));
        }
        return linked;
    }

    /** The latest charge since confirmation outside expected ± tolerance: one open question at a time. */
    void checkPrice(Sub s) {
        var latest = jdbc.queryForList("""
                SELECT id, -amount_minor AS amount FROM transaction
                WHERE subscription_id = ? AND booking_date > coalesce(?, '-infinity'::date)
                ORDER BY booking_date DESC, id DESC LIMIT 1""", s.id(), s.confirmedThrough());
        if (latest.isEmpty()) {
            return;
        }
        long amount = ((Number) latest.getFirst().get("amount")).longValue();
        boolean open = jdbc.queryForObject("""
                SELECT count(*) FROM subscription_alert WHERE subscription_id = ? AND kind = 'PRICE_CHANGE' AND resolution IS NULL""",
                Long.class, s.id()) > 0;
        if (Math.abs(amount - s.expected()) > s.tolerance() && !open) {
            jdbc.update("""
                    INSERT INTO subscription_alert (subscription_id, kind, transaction_id, previous_amount_minor, amount_minor)
                    VALUES (?, 'PRICE_CHANGE', ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT subscription_alert_charge_uq DO NOTHING""",
                    s.id(), latest.getFirst().get("id"), s.expected(), amount);
        }
    }

    /** Nothing by the deadline → MISSED; the next due date missed too → ended by the system. */
    void checkMissed(Sub s, LocalDate today) {
        if (s.next() == null || !today.isAfter(s.deadline(s.next()))) {
            return;
        }
        jdbc.update("""
                INSERT INTO subscription_alert (subscription_id, kind, due_date, amount_minor)
                VALUES (?, 'MISSED', ?, ?)
                ON CONFLICT ON CONSTRAINT subscription_alert_due_uq DO NOTHING""", s.id(), s.next(), s.expected());
        LocalDate following = s.anchor().nextDue(s.next());
        if (today.isAfter(s.deadline(following))) {
            jdbc.update("""
                    UPDATE subscription SET state = 'ENDED', ended_by = 'SYSTEM', updated_at = now() WHERE id = ?""", s.id());
            jdbc.update("""
                    UPDATE subscription_alert SET resolution = 'SYSTEM_ENDED', resolved_at = now()
                    WHERE subscription_id = ? AND resolution IS NULL""", s.id());
        }
    }

    /** Thrown for an answer that does not fit the alert, or one already answered. */
    public static class AnswerException extends IllegalStateException {
        final boolean conflict;

        AnswerException(String message, boolean conflict) {
            super(message);
            this.conflict = conflict;
        }

        public boolean conflict() {
            return conflict;
        }
    }

    /**
     * The user's answer. GOT_IT: the new amount is the expected one from now on. STILL_ACTIVE: skip the missed period.
     * END / CANCELLED: the user ends the subscription.
     */
    @Transactional
    public void answer(long alertId, Action action) {
        Map<String, Object> a = jdbc.queryForList("""
                SELECT a.subscription_id, a.kind, a.amount_minor, a.due_date, a.resolution, t.booking_date, s.state
                FROM subscription_alert a JOIN subscription s ON s.id = a.subscription_id
                LEFT JOIN transaction t ON t.id = a.transaction_id WHERE a.id = ?""", alertId)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("No alert " + alertId));
        if (a.get("resolution") != null || !"CONFIRMED".equals(a.get("state"))) {
            throw new AnswerException("Alert " + alertId + " is closed", true);
        }
        Kind kind = Kind.valueOf((String) a.get("kind"));
        long sub = ((Number) a.get("subscription_id")).longValue();
        boolean fits = kind == Kind.PRICE_CHANGE ? action == Action.GOT_IT || action == Action.END
                : action == Action.STILL_ACTIVE || action == Action.CANCELLED;
        if (!fits) {
            throw new AnswerException(action + " does not answer a " + kind + " alert", false);
        }
        String resolution = switch (action) {
            case GOT_IT -> {
                jdbc.update("""
                        UPDATE subscription SET expected_amount_minor = ?, confirmed_through = ?, updated_at = now()
                        WHERE id = ?""", a.get("amount_minor"), a.get("booking_date"), sub);
                yield "ACKNOWLEDGED";
            }
            case STILL_ACTIVE -> {
                Sub s = reload(sub);
                LocalDate due = ((java.sql.Date) a.get("due_date")).toLocalDate();
                if (s.next() != null && !s.next().isAfter(due)) {
                    jdbc.update("UPDATE subscription SET next_expected_date = ?, updated_at = now() WHERE id = ?",
                            s.anchor().nextDue(due), sub);
                }
                yield "STILL_ACTIVE";
            }
            case END, CANCELLED -> {
                jdbc.update("""
                        UPDATE subscription SET state = 'ENDED', ended_by = 'USER', next_expected_date = NULL,
                            decided_at = now(), updated_at = now()
                        WHERE id = ?""", sub);
                jdbc.update("""
                        UPDATE subscription_alert SET resolution = 'ENDED', resolved_at = now()
                        WHERE subscription_id = ? AND resolution IS NULL AND id <> ?""", sub, alertId);
                yield action == Action.END ? "ENDED" : "CANCELLED";
            }
        };
        jdbc.update("UPDATE subscription_alert SET resolution = ?, resolved_at = now() WHERE id = ?", resolution, alertId);
    }

    /** Open alerts per subscription, for the Recurring screen's status chips. */
    public Map<Long, Kind> openBySubscription() {
        var open = new java.util.HashMap<Long, Kind>();
        jdbc.query("SELECT subscription_id, kind FROM subscription_alert WHERE resolution IS NULL ORDER BY id", rs -> {
            open.put(rs.getLong(1), Kind.valueOf(rs.getString(2)));
        });
        return open;
    }

    private Sub reload(long id) {
        return jdbc.queryForObject("""
                SELECT id, account_id, merchant_id, currency, cadence, anchor_day, anchor_month, expected_amount_minor,
                       tolerance_minor, last_seen, next_expected_date, confirmed_through, state
                FROM subscription WHERE id = ?""",
                (rs, i) -> new Sub(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        Cadence.valueOf(rs.getString(5)), rs.getObject(6, Integer.class), rs.getObject(7, Integer.class),
                        rs.getLong(8), rs.getLong(9), rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class),
                        rs.getObject(12, LocalDate.class), rs.getString(13)), id);
    }
}
