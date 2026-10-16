package dev.costinfl.outflow.split;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settling shared expenses (CP6.22): who owes the user what, and the money in that paid it back. A settlement links
 * part of a money-in transaction to what one person owes for one split (one repayment can cover several splits), or
 * records it as paid outside the bank. The linked part of the money in is {@code transaction.repaid_minor}: figures
 * leave it out ({@code txn.Scope.MINE}), since the user's share was already all that counted.
 *
 * <p>Suggestions are only suggestions: the user settles. A candidate is money in of the same currency, posted, not an
 * own-account transfer, with some of it still unlinked, from {@value #DAYS_BEFORE} days before the expense to
 * {@value #DAYS_AFTER} days after; it is suggested when its amount is what is open, or when its bank text has every
 * word of the person's name (words of 3+ letters, any case, no diacritics).
 */
@Service
public class SettlementService {

    static final long HOUSEHOLD = 1;
    static final int DAYS_BEFORE = 7;
    static final int DAYS_AFTER = 120;
    static final int SUGGESTIONS = 3;

    private final JdbcTemplate jdbc;

    public SettlementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Money in that could be a person's repayment. */
    public record Suggestion(long transactionId, LocalDate bookingDate, String name, long amountMinor,
            long availableMinor, boolean sameAmount, boolean namesPerson) {}

    /** A repayment: part of a money-in transaction, or paid outside the bank (no transaction). */
    public record Settlement(long id, Long transactionId, LocalDate bookingDate, long amountMinor) {}

    /** What one person owes for one split. */
    public record Item(long splitPersonId, long transactionId, LocalDate bookingDate, String merchantName,
            long owedMinor, long settledMinor, List<Settlement> settlements, List<Suggestion> suggestions) {

        public long openMinor() {
            return owedMinor - settledMinor;
        }
    }

    /** One person in one currency: what they still owe, and each split, open ones first. */
    public record Person(String name, String currency, long openMinor, long owedMinor, List<Item> items) {}

    /** Thrown when a repayment cannot be recorded as asked (already settled, not money in, nothing left). */
    public static class SettlementException extends IllegalStateException {
        SettlementException(String message) {
            super(message);
        }
    }

    /** Every person with a split, the ones who still owe the most first. */
    public List<Person> balances() {
        record Row(long personId, String name, long txId, LocalDate date, String merchant, String currency, long owed) {}
        var rows = jdbc.query("""
                SELECT p.id, p.name, t.id, t.booking_date, coalesce(m.display_name, t.description_raw), t.currency,
                       p.amount_minor
                FROM expense_split_person p JOIN expense_split s ON s.id = p.split_id
                JOIN transaction t ON t.id = s.transaction_id LEFT JOIN merchant m ON m.id = t.merchant_id
                WHERE s.household_id = ? AND p.amount_minor > 0
                ORDER BY t.booking_date DESC, t.id DESC, p.position""",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getObject(4, LocalDate.class),
                        rs.getString(5), rs.getString(6), rs.getLong(7)),
                HOUSEHOLD);
        var settlements = new HashMap<Long, List<Settlement>>();
        jdbc.query("""
                SELECT s.split_person_id, s.id, s.transaction_id, t.booking_date, s.amount_minor
                FROM split_settlement s LEFT JOIN transaction t ON t.id = s.transaction_id
                WHERE s.household_id = ? ORDER BY s.id""", rs -> {
            settlements.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Settlement(rs.getLong(2),
                    (Long) rs.getObject(3), rs.getObject(4, LocalDate.class), rs.getLong(5)));
        }, HOUSEHOLD);

        var people = new LinkedHashMap<String, List<Item>>();
        var names = new HashMap<String, String>();
        var currencies = new HashMap<String, String>();
        for (Row r : rows) {
            var done = settlements.getOrDefault(r.personId(), List.of());
            long settled = done.stream().mapToLong(Settlement::amountMinor).sum();
            long open = r.owed() - settled;
            List<Suggestion> suggestions = open > 0 ? suggest(r.name(), r.currency(), r.date(), open) : List.of();
            String key = r.name().toLowerCase(Locale.ROOT) + "|" + r.currency();
            names.putIfAbsent(key, r.name()); // the most recent spelling
            currencies.put(key, r.currency());
            people.computeIfAbsent(key, k -> new ArrayList<>()).add(new Item(r.personId(), r.txId(), r.date(),
                    r.merchant(), r.owed(), settled, done, suggestions));
        }
        return people.entrySet().stream().map(e -> {
            var items = new ArrayList<>(e.getValue());
            items.sort(Comparator.comparing((Item i) -> i.openMinor() == 0)); // stable: newest first within each
            long open = items.stream().mapToLong(Item::openMinor).sum();
            long owed = items.stream().mapToLong(Item::owedMinor).sum();
            return new Person(names.get(e.getKey()), currencies.get(e.getKey()), open, owed, List.copyOf(items));
        }).sorted(Comparator.comparingLong(Person::openMinor).reversed().thenComparing(Person::name)).toList();
    }

    /** Candidates for one open amount, best first: amount and name, then name, then amount; then the closest date. */
    List<Suggestion> suggest(String person, String currency, LocalDate expense, long open) {
        String[] tokens = Arrays.stream(normalize(person).split(" ")).filter(w -> w.length() >= 3).toArray(String[]::new);
        var candidates = jdbc.query("""
                SELECT t.id, t.booking_date, coalesce(m.display_name, t.description_raw), t.amount_minor,
                       t.amount_minor - t.repaid_minor,
                       concat_ws(' ', t.counterparty_raw, t.description_raw, m.display_name)
                FROM transaction t LEFT JOIN merchant m ON m.id = t.merchant_id
                WHERE t.household_id = ? AND t.amount_minor > 0 AND t.currency = ? AND t.status = 'POSTED'
                  AND t.superseded_by IS NULL AND t.transfer_state IS NULL AND t.amount_minor > t.repaid_minor
                  AND t.booking_date BETWEEN ?::date - ? AND ?::date + ?""",
                (rs, i) -> {
                    var words = new java.util.HashSet<>(Arrays.asList(normalize(rs.getString(6)).split(" ")));
                    boolean names = tokens.length > 0 && Arrays.stream(tokens).allMatch(words::contains);
                    long amount = rs.getLong(4);
                    long available = rs.getLong(5);
                    return new Suggestion(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), amount,
                            available, amount == open || available == open, names);
                },
                HOUSEHOLD, currency, expense, DAYS_BEFORE, expense, DAYS_AFTER);
        return candidates.stream().filter(s -> s.sameAmount() || s.namesPerson())
                .sorted(Comparator.comparing((Suggestion s) -> !(s.sameAmount() && s.namesPerson()))
                        .thenComparing(s -> !s.namesPerson())
                        .thenComparingLong(s -> Math.abs(ChronoUnit.DAYS.between(expense, s.bookingDate())))
                        .thenComparingLong(Suggestion::transactionId))
                .limit(SUGGESTIONS).toList();
    }

    /** Upper case, no diacritics, single spaces: "Ana-Maria Ionescu" and "ANA MARIA IONESCU" compare equal. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
    }

    /**
     * Records that a person paid (part of) what they owe for a split: from a money-in transaction, or outside the bank
     * when {@code transactionId} is null. Without an amount, as much as is both open and still unlinked.
     *
     * @return the new settlement's id
     * @throws NoSuchElementException for an unknown split person or transaction
     * @throws SettlementException when nothing is open, the money is not usable, or the amount does not fit
     */
    @Transactional
    public long settle(long splitPersonId, Long transactionId, Long amountMinor) {
        var person = jdbc.query("""
                SELECT p.amount_minor - coalesce((SELECT sum(amount_minor) FROM split_settlement s
                                                  WHERE s.split_person_id = p.id), 0), t.currency
                FROM expense_split_person p JOIN expense_split e ON e.id = p.split_id
                JOIN transaction t ON t.id = e.transaction_id
                WHERE p.id = ? AND e.household_id = ?""",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}, splitPersonId, HOUSEHOLD).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("No split person " + splitPersonId));
        long open = (long) person[0];
        if (open <= 0) {
            throw new SettlementException("Already settled");
        }
        long limit = open;
        if (transactionId != null) {
            var tx = jdbc.query("""
                    SELECT amount_minor, repaid_minor, currency, status, superseded_by, transfer_state
                    FROM transaction WHERE id = ? AND household_id = ?""",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getObject(5), rs.getString(6)},
                    transactionId, HOUSEHOLD).stream().findFirst()
                    .orElseThrow(() -> new NoSuchElementException("No transaction " + transactionId));
            if ((long) tx[0] <= 0 || !"POSTED".equals(tx[3]) || tx[4] != null || tx[5] != null) {
                throw new SettlementException("Only posted money in, not a transfer between your accounts, pays back");
            }
            if (!person[1].equals(tx[2])) {
                throw new SettlementException("The repayment is in another currency than the expense");
            }
            long available = (long) tx[0] - (long) tx[1];
            if (available <= 0) {
                throw new SettlementException("All of this money already pays back other expenses");
            }
            limit = Math.min(open, available);
        }
        long amount = amountMinor == null ? limit : amountMinor;
        if (amount <= 0 || amount > limit) {
            throw new IllegalArgumentException("The amount must be above 0 and at most " + limit);
        }
        long id = jdbc.queryForObject("""
                INSERT INTO split_settlement (household_id, split_person_id, transaction_id, amount_minor)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, HOUSEHOLD, splitPersonId, transactionId, amount);
        if (transactionId != null) {
            recomputeRepaid(transactionId);
        }
        return id;
    }

    /** Undoes a settlement: the amount is open again, and the money in counts as before. False when unknown. */
    @Transactional
    public boolean unsettle(long settlementId) {
        var tx = jdbc.query("SELECT transaction_id FROM split_settlement WHERE id = ? AND household_id = ?",
                (rs, i) -> (Long) rs.getObject(1), settlementId, HOUSEHOLD);
        if (tx.isEmpty()) {
            return false;
        }
        jdbc.update("DELETE FROM split_settlement WHERE id = ?", settlementId);
        if (tx.getFirst() != null) {
            recomputeRepaid(tx.getFirst());
        }
        return true;
    }

    private void recomputeRepaid(long transactionId) {
        jdbc.update("""
                UPDATE transaction SET repaid_minor =
                    (SELECT coalesce(sum(amount_minor), 0) FROM split_settlement WHERE transaction_id = ?)
                WHERE id = ?""", transactionId, transactionId);
    }
}
