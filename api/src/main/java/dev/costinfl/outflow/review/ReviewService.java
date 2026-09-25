package dev.costinfl.outflow.review;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.Cadence;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.RecurrenceDetector;
import dev.costinfl.outflow.recurring.ReminderService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds the review inbox (DESIGN: Review inbox): every decision the system needs, one card per merchant, largest money
 * impact first: subscription suggestions, uncategorized merchants, possible pending/posted duplicates, price changes,
 * missed charges and the reminders the user asked for.
 */
@Service
public class ReviewService {

    private static final BigDecimal PROPOSE = BigDecimal.valueOf(RecurrenceDetector.PROPOSE);

    private final JdbcTemplate jdbc;
    private final ReminderService reminders;

    public ReviewService(JdbcTemplate jdbc, ReminderService reminders) {
        this.jdbc = jdbc;
        this.reminders = reminders;
    }

    public Inbox inbox() {
        var skipped = new HashSet<>(jdbc.queryForList("""
                SELECT card_key FROM review_skip
                WHERE skipped_at >= (SELECT coalesce(max(uploaded_at), '-infinity') FROM statement_file)""", String.class));
        var cards = new ArrayList<ReviewCard>();
        var possible = new ArrayList<ReviewCard>();
        jdbc.query("""
                SELECT s.id, s.merchant_id, s.name, s.currency, s.cadence, s.expected_amount_minor, s.amount_kind,
                       s.first_seen, s.confidence, s.next_expected_date,
                       count(t.id), coalesce(sum(abs(t.amount_minor)), 0), s.direction
                FROM subscription s LEFT JOIN transaction t ON t.subscription_id = s.id
                WHERE s.state = 'PROPOSED'
                GROUP BY s.id""", rs -> {
            BigDecimal confidence = rs.getBigDecimal(9);
            var card = new ReviewCard("subscription:" + rs.getLong(1), Kind.SUBSCRIPTION, rs.getLong(12),
                    rs.getString(4), rs.getLong(2), rs.getString(3), rs.getLong(1), Cadence.valueOf(rs.getString(5)),
                    rs.getLong(6), AmountKind.valueOf(rs.getString(7)), rs.getObject(8, LocalDate.class),
                    rs.getInt(11), confidence, rs.getObject(10, LocalDate.class), null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, Direction.valueOf(rs.getString(13)));
            if (!skipped.contains(card.key())) {
                (confidence.compareTo(PROPOSE) >= 0 ? cards : possible).add(card);
            }
        });
        jdbc.query("""
                SELECT t.merchant_id, m.display_name, t.currency, count(*), sum(abs(t.amount_minor)),
                       count(*) FILTER (WHERE t.amount_minor < 0), coalesce(sum(-t.amount_minor) FILTER (WHERE t.amount_minor < 0), 0),
                       count(*) FILTER (WHERE t.amount_minor >= 0), coalesce(sum(t.amount_minor) FILTER (WHERE t.amount_minor >= 0), 0),
                       min(t.booking_date)
                FROM transaction t JOIN merchant m ON m.id = t.merchant_id
                WHERE t.category_id IS NULL AND t.superseded_by IS NULL
                GROUP BY t.merchant_id, m.display_name, t.currency""", rs -> {
            var card = new ReviewCard("merchant:" + rs.getLong(1) + ":" + rs.getString(3), Kind.UNCATEGORIZED_MERCHANT,
                    rs.getLong(5), rs.getString(3), rs.getLong(1), rs.getString(2),
                    null, null, null, null, null, null, null, null, rs.getInt(4), null, null, null, null, null,
                    null, null, null, null, rs.getInt(6), rs.getLong(7), rs.getInt(8), rs.getLong(9),
                    rs.getObject(10, LocalDate.class), null);
            if (!skipped.contains(card.key())) {
                cards.add(card);
            }
        });
        // Possible duplicates: open soft-match questions (DESIGN: "Same 120 RON at Emag, pending and posted").
        jdbc.query("""
                SELECT r.id, p.merchant_id, m.display_name, p.currency, p.booking_date, p.amount_minor,
                       q.booking_date, q.amount_minor
                FROM soft_match_review r
                JOIN transaction p ON p.id = r.pending_transaction_id
                JOIN transaction q ON q.id = r.posted_transaction_id
                JOIN merchant m ON m.id = p.merchant_id
                WHERE r.resolution IS NULL AND p.superseded_by IS NULL AND p.status = 'PENDING'
                  AND NOT EXISTS (SELECT 1 FROM transaction x WHERE x.superseded_by = q.id)""", rs -> {
            var card = new ReviewCard("duplicate:" + rs.getLong(1), Kind.POSSIBLE_DUPLICATE, Math.abs(rs.getLong(6)),
                    rs.getString(4), rs.getLong(2), rs.getString(3), null, null, null, null, null, null, null, null, null,
                    rs.getLong(1), rs.getObject(5, LocalDate.class), rs.getLong(6), rs.getObject(7, LocalDate.class),
                    rs.getLong(8), null, null, null, null, null, null, null, null, null, null);
            if (!skipped.contains(card.key())) {
                cards.add(card);
            }
        });
        // Price changes and missed charges on confirmed subscriptions (DESIGN: "Netflix went from 49.99 to 59.99 RON",
        // "Gym usually charges around the 5th: nothing this month"). Money affected: the monthly equivalent.
        jdbc.query("""
                SELECT a.id, a.kind, a.previous_amount_minor, a.amount_minor, a.due_date,
                       s.id, s.merchant_id, s.name, s.currency, s.cadence, s.expected_amount_minor, s.amount_kind,
                       s.direction
                FROM subscription_alert a JOIN subscription s ON s.id = a.subscription_id
                WHERE a.resolution IS NULL AND s.state = 'CONFIRMED'""", rs -> {
            boolean price = rs.getString(2).equals("PRICE_CHANGE");
            Cadence cadence = Cadence.valueOf(rs.getString(10));
            var card = new ReviewCard("alert:" + rs.getLong(1), price ? Kind.PRICE_CHANGE : Kind.MISSED_CHARGE,
                    cadence.monthlyMinor(rs.getLong(price ? 4 : 11)), rs.getString(9), rs.getLong(7), rs.getString(8),
                    rs.getLong(6), cadence, rs.getLong(11), AmountKind.valueOf(rs.getString(12)), null, null, null, null,
                    null, null, null, null, null, null, rs.getLong(1), (Long) rs.getObject(3), rs.getLong(4),
                    rs.getObject(5, LocalDate.class), null, null, null, null, null, Direction.valueOf(rs.getString(13)));
            if (!skipped.contains(card.key())) {
                cards.add(card);
            }
        });
        // Reminders the user asked for ("remind me before next charge"): money affected is the charge itself.
        for (ReminderService.Due d : reminders.due()) {
            var card = new ReviewCard("reminder:" + d.subscriptionId() + ":" + d.dueDate(), Kind.UPCOMING_CHARGE,
                    d.expectedAmountMinor(), d.currency(), d.merchantId(), d.name(), d.subscriptionId(), d.cadence(),
                    d.expectedAmountMinor(), AmountKind.valueOf(d.amountKind()), null, null, null, null, null, null,
                    null, null, null, null, null, null, null, d.dueDate(), null, null, null, null, null, Direction.OUT);
            if (!skipped.contains(card.key())) {
                cards.add(card);
            }
        }
        Comparator<ReviewCard> byImpact = Comparator.comparingLong(ReviewCard::affectedMinor).reversed()
                .thenComparing(ReviewCard::key);
        cards.sort(byImpact);
        possible.sort(byImpact);
        return new Inbox(cards, possible, cards.size());
    }

    /** Hides a card until the next upload. */
    public void skip(String key) {
        jdbc.update("""
                INSERT INTO review_skip (card_key) VALUES (?)
                ON CONFLICT (card_key) DO UPDATE SET skipped_at = now()""", key);
    }
}
