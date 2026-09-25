package dev.costinfl.outflow.txn;

import dev.costinfl.outflow.category.CategoryService;
import dev.costinfl.outflow.ingest.account.IbanHasher;
import dev.costinfl.outflow.ingest.parse.Iban;
import java.nio.ByteBuffer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairs transfers between the household's own accounts (DESIGN: Internal transfer detection), so they are never
 * spending and never a subscription. A pair is money out of one account and the same amount into another, same
 * currency, within {@value #MAX_BUSINESS_DAYS} business days. A counterparty IBAN that names another own account confirms
 * a pair, rules out pairs with any other account, and on its own marks a one-sided transfer as PROVISIONAL.
 *
 * <p>Matching is greedy by smallest business-day gap (IBAN-confirmed first within a gap). When one transaction has two
 * equally good partners the tie is left unpaired: a wrong pair would silently hide real spending.
 *
 * <p>Pairs are derived data: every run recomputes them from all transactions and applies only the difference, so the
 * result does not depend on the order statements were uploaded in (a later upload can turn a pair into a tie).
 */
@Service
public class TransferService {

    static final long HOUSEHOLD = 1;
    static final int MAX_BUSINESS_DAYS = 3;

    private final JdbcTemplate jdbc;
    private final IbanHasher hasher;
    private final CategoryService categories;

    public TransferService(JdbcTemplate jdbc, IbanHasher hasher, CategoryService categories) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.categories = categories;
    }

    /** New pairs and newly provisional transactions from one run (dissolved ones are not counted). */
    public record Result(int pairs, int provisional) {

        /** Transactions newly recognised as own-account transfers. */
        public int transactions() {
            return 2 * pairs + provisional;
        }
    }

    record Tx(long id, long accountId, LocalDate date, long amountMinor, String currency, Long namedAccount,
            String state, boolean userCategorized) {}

    record Edge(Tx out, Tx in, int businessDays, boolean iban) {}

    @Transactional
    public Result pairAll() {
        var ownAccounts = new HashMap<ByteBuffer, Long>();
        jdbc.query("SELECT id, iban_hash FROM account WHERE household_id = ? AND iban_hash IS NOT NULL", rs -> {
            ownAccounts.put(ByteBuffer.wrap(rs.getBytes(2)), rs.getLong(1));
        }, HOUSEHOLD);

        // Candidates: everything not given a non-transfer category by the user (a user decision wins).
        List<Tx> txs = jdbc.query("""
                SELECT t.id, t.account_id, t.booking_date, t.amount_minor, t.currency,
                       concat_ws(' ', t.counterparty_raw, t.description_raw) AS text, t.transfer_state,
                       t.category_source = 'USER' AS user_categorized
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.household_id = ? AND t.amount_minor <> 0 AND t.superseded_by IS NULL
                  AND (t.category_source IS DISTINCT FROM 'USER' OR c.kind = 'TRANSFER')""", (rs, i) -> {
            long account = rs.getLong(2);
            Long named = null;
            for (Iban iban : Iban.findAll(rs.getString(6))) {
                Long own = ownAccounts.get(ByteBuffer.wrap(hasher.hash(iban)));
                if (own != null && own != account) {
                    named = own;
                    break;
                }
            }
            return new Tx(rs.getLong(1), account, rs.getObject(3, LocalDate.class), rs.getLong(4), rs.getString(5), named,
                    rs.getString(7), rs.getBoolean(8));
        }, HOUSEHOLD);

        var used = new HashSet<Long>();
        List<Edge> wanted = choose(candidateEdges(txs), used);

        // Existing pairs that are no longer the right answer are dissolved first (e.g. now a tie).
        record Pair(long out, long in) {}
        var existing = new HashMap<Pair, Long>();
        jdbc.query("SELECT id, out_transaction_id, in_transaction_id FROM transfer_pair WHERE household_id = ?", rs -> {
            existing.put(new Pair(rs.getLong(2), rs.getLong(3)), rs.getLong(1));
        }, HOUSEHOLD);
        var keep = new HashSet<Pair>();
        wanted.forEach(e -> keep.add(new Pair(e.out().id(), e.in().id())));
        boolean dissolved = false;
        for (var entry : existing.entrySet()) {
            if (!keep.contains(entry.getKey())) {
                clear("transfer_pair_id = ?", entry.getValue());
                jdbc.update("DELETE FROM transfer_pair WHERE id = ?", entry.getValue());
                dissolved = true;
            }
        }

        int pairs = 0;
        for (Edge e : wanted) {
            if (!existing.containsKey(new Pair(e.out().id(), e.in().id()))) {
                pair(e);
                pairs++;
            }
        }
        int provisional = 0;
        for (Tx t : txs) {
            if (used.contains(t.id())) {
                continue;
            }
            if (t.namedAccount() != null) {
                if (!"PROVISIONAL".equals(t.state())) {
                    jdbc.update("UPDATE transaction SET transfer_pair_id = NULL, transfer_state = 'PROVISIONAL', "
                            + "transfer_account_id = ? WHERE id = ?", t.namedAccount(), t.id());
                    categorizeAsTransfer(t);
                    provisional++;
                }
            } else if ("PROVISIONAL".equals(t.state())) {
                clear("id = ?", t.id());
                dissolved = true;
            }
        }
        if (dissolved) {
            categories.categorizeAll(); // what is no longer a transfer gets its ordinary category back
        }
        return new Result(pairs, provisional);
    }

    /** No longer a transfer: drop the transfer fields and the SYSTEM category (a user's category stays). */
    private void clear(String where, long arg) {
        jdbc.update("""
                UPDATE transaction SET transfer_pair_id = NULL, transfer_state = NULL, transfer_account_id = NULL,
                    category_id = CASE WHEN category_source = 'SYSTEM' THEN NULL ELSE category_id END,
                    category_confidence = CASE WHEN category_source = 'SYSTEM' THEN NULL ELSE category_confidence END,
                    category_source = CASE WHEN category_source = 'SYSTEM' THEN NULL ELSE category_source END
                WHERE """ + " " + where, arg);
    }

    /** Every plausible (out, in) pair, best first: smallest gap, then IBAN-confirmed. */
    static List<Edge> candidateEdges(List<Tx> txs) {
        record Key(String currency, long amount) {}
        var ins = new HashMap<Key, List<Tx>>();
        for (Tx t : txs) {
            if (t.amountMinor() > 0) {
                ins.computeIfAbsent(new Key(t.currency(), t.amountMinor()), k -> new ArrayList<>()).add(t);
            }
        }
        var edges = new ArrayList<Edge>();
        for (Tx out : txs) {
            if (out.amountMinor() >= 0) {
                continue;
            }
            for (Tx in : ins.getOrDefault(new Key(out.currency(), -out.amountMinor()), List.of())) {
                if (in.accountId() == out.accountId()
                        || (out.namedAccount() != null && out.namedAccount() != in.accountId())
                        || (in.namedAccount() != null && in.namedAccount() != out.accountId())) {
                    continue;
                }
                int gap = businessDays(out.date(), in.date());
                if (gap <= MAX_BUSINESS_DAYS) {
                    edges.add(new Edge(out, in, gap, out.namedAccount() != null || in.namedAccount() != null));
                }
            }
        }
        edges.sort(Comparator.comparingInt(Edge::businessDays).thenComparing(Edge::iban, Comparator.reverseOrder())
                .thenComparingLong(e -> e.out().id()).thenComparingLong(e -> e.in().id()));
        return edges;
    }

    /**
     * Greedy matching, one quality level (gap, IBAN) at a time. Within a level, a transaction with more than one free
     * partner is a tie: it and its partners at that level stay unpaired for good (DESIGN: "ties go to review").
     */
    static List<Edge> choose(List<Edge> edges, Set<Long> used) {
        var chosen = new ArrayList<Edge>();
        var blocked = new HashSet<Long>();
        int i = 0;
        while (i < edges.size()) {
            int j = i;
            while (j < edges.size() && edges.get(j).businessDays() == edges.get(i).businessDays()
                    && edges.get(j).iban() == edges.get(i).iban()) {
                j++;
            }
            var level = edges.subList(i, j).stream()
                    .filter(e -> free(e.out(), used, blocked) && free(e.in(), used, blocked)).toList();
            var degree = new HashMap<Long, Integer>();
            for (Edge e : level) {
                degree.merge(e.out().id(), 1, Integer::sum);
                degree.merge(e.in().id(), 1, Integer::sum);
            }
            for (Edge e : level) {
                if (degree.get(e.out().id()) == 1 && degree.get(e.in().id()) == 1) {
                    chosen.add(e);
                    used.add(e.out().id());
                    used.add(e.in().id());
                } else {
                    blocked.add(e.out().id());
                    blocked.add(e.in().id());
                }
            }
            i = j;
        }
        return chosen;
    }

    private static boolean free(Tx t, Set<Long> used, Set<Long> blocked) {
        return !used.contains(t.id()) && !blocked.contains(t.id());
    }

    /** Weekdays after the earlier date up to and including the later one: Friday → Monday is 1. */
    static int businessDays(LocalDate a, LocalDate b) {
        LocalDate from = a.isBefore(b) ? a : b;
        LocalDate to = a.isBefore(b) ? b : a;
        if (to.isAfter(from.plusDays(14))) {
            return Integer.MAX_VALUE;
        }
        int days = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private void pair(Edge e) {
        long pairId = jdbc.queryForObject("""
                INSERT INTO transfer_pair (household_id, out_transaction_id, in_transaction_id, method, business_days)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class,
                HOUSEHOLD, e.out().id(), e.in().id(), e.iban() ? "IBAN" : "AMOUNT_DATE", e.businessDays());
        for (Tx t : List.of(e.out(), e.in())) {
            long other = t == e.out() ? e.in().accountId() : e.out().accountId();
            jdbc.update("UPDATE transaction SET transfer_pair_id = ?, transfer_state = 'PAIRED', transfer_account_id = ? WHERE id = ?",
                    pairId, other, t.id());
            categorizeAsTransfer(t);
        }
    }

    /** Category TRANSFER, source SYSTEM, unless the user categorized the transaction (then it already is a transfer). */
    private void categorizeAsTransfer(Tx t) {
        if (!t.userCategorized()) {
            jdbc.update("""
                    UPDATE transaction SET category_id = (SELECT id FROM category WHERE code = 'TRANSFER'),
                        category_source = 'SYSTEM', category_confidence = 1.00
                    WHERE id = ?""", t.id());
        }
    }

    /** Transfers per state, for tests and the import summary. */
    public Map<String, Long> counts() {
        var counts = new HashMap<String, Long>();
        jdbc.query("SELECT transfer_state, count(*) FROM transaction WHERE transfer_state IS NOT NULL GROUP BY 1",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }
}
