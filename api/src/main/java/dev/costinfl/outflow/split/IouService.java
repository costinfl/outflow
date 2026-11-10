package dev.costinfl.outflow.split;

import dev.costinfl.outflow.split.SettlementService.Suggestion;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "They paid, I owe" (CP6.23): someone paid for something shared and the user owes them a share. No bank money moved
 * for the user then, so an IOU counts nowhere. The money out that pays it back is linked to it (or the IOU is marked
 * paid outside the bank); that payment is the user's spending, so linking gives it the IOU's category, as the user's
 * own decision ({@code category_source = 'USER'}). A category the user already set by hand is kept.
 *
 * <p>Suggestions mirror {@link SettlementService}'s, for money out.
 */
@Service
public class IouService {

    static final long HOUSEHOLD = 1;

    private final JdbcTemplate jdbc;

    public IouService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Payment(long id, Long transactionId, LocalDate bookingDate, long amountMinor) {}

    public record Iou(long id, String person, String description, LocalDate incurredOn, String currency,
            long amountMinor, Long categoryId, String categoryName, long paidMinor, List<Payment> payments,
            List<Suggestion> suggestions) {

        public long openMinor() {
            return amountMinor - paidMinor;
        }
    }

    /** One person the user owes, in one currency: what is still open, and each IOU, open ones first. */
    public record Creditor(String name, String currency, long openMinor, long owedMinor, List<Iou> ious) {}

    /** Thrown when an IOU cannot change as asked (already paid, payments recorded, not money out, nothing left). */
    public static class IouException extends IllegalStateException {
        IouException(String message) {
            super(message);
        }
    }

    /**
     * Records what the user owes.
     *
     * @throws IllegalArgumentException for a blank person or description, an unknown currency, an amount not above 0,
     *     or an unknown category
     */
    @Transactional
    public long add(String person, String description, LocalDate incurredOn, String currency, long amountMinor,
            Long categoryId) {
        String name = clean(person, 60, "Who paid");
        String what = clean(description, 120, "What it was for");
        if (incurredOn == null) {
            throw new IllegalArgumentException("When was it?");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO code like RON");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency " + currency);
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("The amount must be above 0");
        }
        if (categoryId != null && jdbc.queryForObject("SELECT count(*) FROM category WHERE id = ?", Long.class,
                categoryId) == 0) {
            throw new IllegalArgumentException("Unknown category " + categoryId);
        }
        return jdbc.queryForObject("""
                INSERT INTO iou (household_id, person, description, incurred_on, currency, amount_minor, category_id)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, HOUSEHOLD, name, what, incurredOn, currency,
                amountMinor, categoryId);
    }

    private static String clean(String text, int max, String label) {
        String s = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (s.isEmpty() || s.length() > max) {
            throw new IllegalArgumentException(label + ": 1 to " + max + " characters");
        }
        return s;
    }

    /**
     * Forgets an IOU. False when unknown.
     *
     * @throws IouException when part of it was paid (undo those payments first)
     */
    @Transactional
    public boolean delete(long iouId) {
        if (jdbc.queryForObject("SELECT count(*) FROM iou_payment WHERE iou_id = ?", Long.class, iouId) > 0) {
            throw new IouException("Part of this was paid; undo those payments first");
        }
        return jdbc.update("DELETE FROM iou WHERE id = ? AND household_id = ?", iouId, HOUSEHOLD) > 0;
    }

    /** Everyone the user owes, the most first. */
    public List<Creditor> list() {
        var payments = new HashMap<Long, List<Payment>>();
        jdbc.query("""
                SELECT p.iou_id, p.id, p.transaction_id, t.booking_date, p.amount_minor
                FROM iou_payment p LEFT JOIN transaction t ON t.id = p.transaction_id
                WHERE p.household_id = ? ORDER BY p.id""", rs -> {
            payments.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Payment(rs.getLong(2),
                    (Long) rs.getObject(3), rs.getObject(4, LocalDate.class), rs.getLong(5)));
        }, HOUSEHOLD);
        var ious = jdbc.query("""
                SELECT i.id, i.person, i.description, i.incurred_on, i.currency, i.amount_minor, i.category_id, c.name
                FROM iou i LEFT JOIN category c ON c.id = i.category_id
                WHERE i.household_id = ? ORDER BY i.incurred_on DESC, i.id DESC""", (rs, n) -> {
            long id = rs.getLong(1);
            var paid = payments.getOrDefault(id, List.of());
            long amount = rs.getLong(6);
            long open = amount - paid.stream().mapToLong(Payment::amountMinor).sum();
            LocalDate date = rs.getObject(4, LocalDate.class);
            return new Iou(id, rs.getString(2), rs.getString(3), date, rs.getString(5), amount,
                    (Long) rs.getObject(7), rs.getString(8), amount - open, paid,
                    open > 0 ? suggest(rs.getString(2), rs.getString(5), date, open) : List.of());
        }, HOUSEHOLD);
        var byPerson = new LinkedHashMap<String, List<Iou>>();
        for (Iou i : ious) {
            byPerson.computeIfAbsent(i.person().toLowerCase(Locale.ROOT) + "|" + i.currency(), k -> new ArrayList<>()).add(i);
        }
        return byPerson.values().stream().map(list -> {
            var sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparing((Iou i) -> i.openMinor() == 0));
            return new Creditor(list.getFirst().person(), list.getFirst().currency(),
                    list.stream().mapToLong(Iou::openMinor).sum(), list.stream().mapToLong(Iou::amountMinor).sum(),
                    List.copyOf(sorted));
        }).sorted(Comparator.comparingLong(Creditor::openMinor).reversed().thenComparing(Creditor::name)).toList();
    }

    /** Money out that could pay an IOU back: same rules as repayments, signs reversed. */
    List<Suggestion> suggest(String person, String currency, LocalDate incurred, long open) {
        String[] tokens = Arrays.stream(SettlementService.normalize(person).split(" ")).filter(w -> w.length() >= 3)
                .toArray(String[]::new);
        var candidates = jdbc.query("""
                SELECT t.id, t.booking_date, coalesce(m.display_name, t.description_raw), -t.amount_minor,
                       -t.amount_minor - coalesce((SELECT sum(p.amount_minor) FROM iou_payment p
                                                   WHERE p.transaction_id = t.id), 0),
                       concat_ws(' ', t.counterparty_raw, t.description_raw, m.display_name)
                FROM transaction t LEFT JOIN merchant m ON m.id = t.merchant_id
                WHERE t.household_id = ? AND t.amount_minor < 0 AND t.currency = ? AND t.status = 'POSTED'
                  AND t.superseded_by IS NULL AND t.transfer_state IS NULL
                  AND t.booking_date BETWEEN ?::date - ? AND ?::date + ?""",
                (rs, i) -> {
                    var words = new HashSet<>(Arrays.asList(SettlementService.normalize(rs.getString(6)).split(" ")));
                    boolean names = tokens.length > 0 && Arrays.stream(tokens).allMatch(words::contains);
                    long amount = rs.getLong(4);
                    long available = rs.getLong(5);
                    return new Suggestion(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), amount,
                            available, amount == open || available == open, names);
                },
                HOUSEHOLD, currency, incurred, SettlementService.DAYS_BEFORE, incurred, SettlementService.DAYS_AFTER);
        return candidates.stream().filter(s -> s.availableMinor() > 0 && (s.sameAmount() || s.namesPerson()))
                .sorted(Comparator.comparing((Suggestion s) -> !(s.sameAmount() && s.namesPerson()))
                        .thenComparing(s -> !s.namesPerson())
                        .thenComparingLong(s -> Math.abs(ChronoUnit.DAYS.between(incurred, s.bookingDate())))
                        .thenComparingLong(Suggestion::transactionId))
                .limit(SettlementService.SUGGESTIONS).toList();
    }

    /**
     * Records that the user paid (part of) an IOU: from a money-out transaction, or outside the bank when
     * {@code transactionId} is null. Without an amount, as much as is both open and still unlinked. A linked payment
     * takes the IOU's category, unless the user already categorized it by hand.
     *
     * @throws NoSuchElementException for an unknown IOU or transaction
     * @throws IouException when nothing is open, the money is not usable, or another currency
     */
    @Transactional
    public long pay(long iouId, Long transactionId, Long amountMinor) {
        var iou = jdbc.query("""
                SELECT i.amount_minor - coalesce((SELECT sum(amount_minor) FROM iou_payment p WHERE p.iou_id = i.id), 0),
                       i.currency, i.category_id
                FROM iou i WHERE i.id = ? AND i.household_id = ?""",
                (rs, n) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getObject(3)}, iouId, HOUSEHOLD)
                .stream().findFirst().orElseThrow(() -> new NoSuchElementException("No IOU " + iouId));
        long open = (long) iou[0];
        if (open <= 0) {
            throw new IouException("Already paid");
        }
        long limit = open;
        if (transactionId != null) {
            var tx = jdbc.query("""
                    SELECT -amount_minor - coalesce((SELECT sum(amount_minor) FROM iou_payment WHERE transaction_id = t.id), 0),
                           amount_minor, currency, status, superseded_by, transfer_state
                    FROM transaction t WHERE id = ? AND household_id = ?""",
                    (rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5), rs.getString(6)},
                    transactionId, HOUSEHOLD).stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("No transaction " + transactionId));
            if ((long) tx[1] >= 0 || !"POSTED".equals(tx[3]) || tx[4] != null || tx[5] != null) {
                throw new IouException("Only posted money out, not a transfer between your accounts, pays an IOU");
            }
            if (!iou[1].equals(tx[2])) {
                throw new IouException("The payment is in another currency than the IOU");
            }
            long available = (long) tx[0];
            if (available <= 0) {
                throw new IouException("All of this money already pays other IOUs");
            }
            limit = Math.min(open, available);
        }
        long amount = amountMinor == null ? limit : amountMinor;
        if (amount <= 0 || amount > limit) {
            throw new IllegalArgumentException("The amount must be above 0 and at most " + limit);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO iou_payment (household_id, iou_id, transaction_id, amount_minor) VALUES (?, ?, ?, ?)
                RETURNING id""", Long.class, HOUSEHOLD, iouId, transactionId, amount);
        if (transactionId != null && iou[2] != null) {
            jdbc.update("""
                    UPDATE transaction SET category_id = ?, category_source = 'USER', category_confidence = 1.00
                    WHERE id = ? AND category_source IS DISTINCT FROM 'USER'""", iou[2], transactionId);
        }
        return id;
    }

    /** Undoes a payment: the IOU is open again. The payment keeps its category (change it on the transaction). */
    @Transactional
    public boolean unpay(long paymentId) {
        return jdbc.update("DELETE FROM iou_payment WHERE id = ? AND household_id = ?", paymentId, HOUSEHOLD) > 0;
    }
}
