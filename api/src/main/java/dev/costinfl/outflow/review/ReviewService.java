package dev.costinfl.outflow.review;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.Cadence;
import dev.costinfl.outflow.recurring.Candidate.AmountKind;
import dev.costinfl.outflow.recurring.RecurrenceDetector;
import dev.costinfl.outflow.recurring.ReminderService;
import dev.costinfl.outflow.review.ReviewCard.Inbox;
import dev.costinfl.outflow.review.ReviewCard.Kind;
import dev.costinfl.outflow.txn.Scope;
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

    /** "Is this right?" cards shown at a time. */
    static final int CONFIRM_AT_ONCE = 5;

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
                    null, null, null, null, null, null, null, null, null, Direction.valueOf(rs.getString(13)), null, null);
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
                    rs.getObject(10, LocalDate.class), null, null, null);
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
                    rs.getLong(8), null, null, null, null, null, null, null, null, null, null, null, null);
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
                    rs.getObject(5, LocalDate.class), null, null, null, null, null, Direction.valueOf(rs.getString(13)), null,
                    null);
            if (!skipped.contains(card.key())) {
                cards.add(card);
            }
        });
        // "Is this right?" (DESIGN: accuracy = categorized and reviewed): the merchants whose spending only a keyword
        // categorized, most money first, a few at a time: answering one brings up the next.
        int asked = 0;
        for (var r : jdbc.queryForList("""
                SELECT t.merchant_id, m.display_name, t.currency, c.id AS category_id, c.name AS category_name,
                       count(*) AS n, sum(-t.amount_minor) AS spent
                FROM transaction t JOIN merchant m ON m.id = t.merchant_id JOIN category c ON c.id = t.category_id
                WHERE t.amount_minor < 0 AND c.kind = 'SPEND' AND t.superseded_by IS NULL AND NOT""" + " " + Scope.REVIEWED + """

                GROUP BY t.merchant_id, m.display_name, t.currency, c.id, c.name
                HAVING bool_and(t.category_source = 'KEYWORD')
                ORDER BY spent DESC, t.merchant_id""")) {
            if (asked == CONFIRM_AT_ONCE) {
                break;
            }
            long merchant = ((Number) r.get("merchant_id")).longValue();
            var card = new ReviewCard("category:" + merchant + ":" + r.get("currency"), Kind.CONFIRM_CATEGORY,
                    ((Number) r.get("spent")).longValue(), (String) r.get("currency"), merchant, (String) r.get("display_name"),
                    null, null, null, null, null, null, null, null, ((Number) r.get("n")).intValue(), null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, Direction.OUT,
                    ((Number) r.get("category_id")).longValue(), (String) r.get("category_name"));
            if (!skipped.contains(card.key())) {
                cards.add(card);
                asked++;
            }
        }
        // Reminders the user asked for ("remind me before next charge"): money affected is the charge itself.
        for (ReminderService.Due d : reminders.due()) {
            var card = new ReviewCard("reminder:" + d.subscriptionId() + ":" + d.dueDate(), Kind.UPCOMING_CHARGE,
                    d.expectedAmountMinor(), d.currency(), d.merchantId(), d.name(), d.subscriptionId(), d.cadence(),
                    d.expectedAmountMinor(), AmountKind.valueOf(d.amountKind()), null, null, null, null, null, null,
                    null, null, null, null, null, null, null, d.dueDate(), null, null, null, null, null, Direction.OUT, null, null);
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
