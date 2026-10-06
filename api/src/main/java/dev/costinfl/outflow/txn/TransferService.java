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
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairs transfers between the household's own accounts (DESIGN: Internal transfer detection), so they are never
 * spending and never a subscription. A pair is money out of one account and the same amount into another, same
 * currency, within {@value #MAX_BUSINESS_DAYS} business days. Across currencies (CP6.19) the amounts must be within the
 * user's approximate rate ({@link FxRates}); without a rate for the pair, only an IBAN naming the other account pairs
 * them. A counterparty IBAN that names another own account confirms a pair, rules out pairs with any other account,
 * and on its own marks a one-sided transfer as PROVISIONAL.
 *
 * <p>Matching is greedy by smallest business-day gap (IBAN-confirmed first within a gap). When one transaction has two
 * equally good partners the tie is left unpaired: a wrong pair would silently hide real spending.
 *
 * <p>Pairs are derived data: every run recomputes them from all transactions and applies only the difference, so the
 * result does not depend on the order statements were uploaded in (a later upload can turn a pair into a tie).
 *
 * <p>Ties go to review (CP6.12, "Which transfer is this?"): {@link #ties()} lists them, one question per group of tied
 * transactions, and {@link #decide} stores the user's answer. A picked pair (SAME) is made before any automatic one and
 * kept for good; a rejected one (DIFFERENT) never forms. Both survive every rerun.
 */
@Service
public class TransferService {

    static final long HOUSEHOLD = 1;
    static final int MAX_BUSINESS_DAYS = 3;

    private final JdbcTemplate jdbc;
    private final IbanHasher hasher;
    private final CategoryService categories;
    private final FxRates fxRates;

    public TransferService(JdbcTemplate jdbc, IbanHasher hasher, CategoryService categories, FxRates fxRates) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.categories = categories;
        this.fxRates = fxRates;
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

    /**
     * A possible pair; {@code fx}: two currencies (the amounts are not equal); {@code user}: the user picked it on a tie
     * card.
     */
    record Edge(Tx out, Tx in, int businessDays, boolean iban, boolean fx, boolean user) {}

    /** The user's rates by pair ({@link FxRates#key}); a cross-currency pair without one needs an IBAN. */
    record Rates(Map<String, FxRates.Rate> byPair) {

        static final Rates NONE = new Rates(Map.of());

        /** Null: no rate for these currencies; otherwise whether the amounts are within its tolerance. */
        Boolean close(Tx out, Tx in) {
            FxRates.Rate rate = byPair.get(FxRates.key(out.currency(), in.currency()));
            return rate == null ? null : rate.matches(out.amountMinor(), out.currency(), in.amountMinor(), in.currency());
        }
    }

    /** One run's answer: the pairs to have (the user's first), the tied edges left unpaired, and every candidate. */
    record Plan(List<Tx> txs, List<Edge> wanted, List<Edge> tied, Set<Long> used) {}

    private Plan plan() {
        List<Tx> txs = load();
        var rates = new Rates(fxRates.all());
        var byId = new HashMap<Long, Tx>();
        txs.forEach(t -> byId.put(t.id(), t));
        var used = new HashSet<Long>();
        var wanted = new ArrayList<Edge>();
        // The user's picks come first, while both sides are still candidates (a later non-transfer category wins).
        jdbc.query("""
                SELECT out_transaction_id, in_transaction_id FROM transfer_decision
                WHERE household_id = ? AND decision = 'SAME' ORDER BY id""", rs -> {
            Tx out = byId.get(rs.getLong(1));
            Tx in = byId.get(rs.getLong(2));
            if (out != null && in != null && !used.contains(out.id()) && !used.contains(in.id())) {
                Edge e = edge(out, in, true, rates);
                if (e != null) {
                    wanted.add(e);
                    used.add(out.id());
                    used.add(in.id());
                }
            }
        }, HOUSEHOLD);
        var different = new HashSet<>(jdbc.query("""
                SELECT out_transaction_id, in_transaction_id FROM transfer_decision
                WHERE household_id = ? AND decision = 'DIFFERENT'""", (rs, i) -> List.of(rs.getLong(1), rs.getLong(2)),
                HOUSEHOLD));
        var edges = candidateEdges(txs, rates).stream()
                .filter(e -> !used.contains(e.out().id()) && !used.contains(e.in().id())
                        && !different.contains(List.of(e.out().id(), e.in().id())))
                .toList();
        var tied = new ArrayList<Edge>();
        wanted.addAll(choose(edges, used, tied));
        return new Plan(txs, wanted, tied, used);
    }

    private List<Tx> load() {
        var ownAccounts = new HashMap<ByteBuffer, Long>();
        jdbc.query("SELECT id, iban_hash FROM account WHERE household_id = ? AND iban_hash IS NOT NULL", rs -> {
            ownAccounts.put(ByteBuffer.wrap(rs.getBytes(2)), rs.getLong(1));
        }, HOUSEHOLD);

        // Candidates: everything not given a non-transfer category by the user (a user decision wins).
        return jdbc.query("""
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
    }

    @Transactional
    public Result pairAll() {
        Plan plan = plan();
        Set<Long> used = plan.used();
        List<Edge> wanted = plan.wanted();

        // Existing pairs that are no longer the right answer are dissolved first (e.g. now a tie).
        record Pair(long out, long in) {}
        var existing = new HashMap<Pair, Long>();
        var existingSource = new HashMap<Pair, String>();
        jdbc.query("SELECT id, out_transaction_id, in_transaction_id, source FROM transfer_pair WHERE household_id = ?", rs -> {
            var key = new Pair(rs.getLong(2), rs.getLong(3));
            existing.put(key, rs.getLong(1));
            existingSource.put(key, rs.getString(4));
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
            var key = new Pair(e.out().id(), e.in().id());
            if (!existing.containsKey(key)) {
                pair(e);
                pairs++;
            } else if (!source(e).equals(existingSource.get(key))) {
                jdbc.update("UPDATE transfer_pair SET source = ? WHERE id = ?", source(e), existing.get(key));
            }
        }
        int provisional = 0;
        for (Tx t : plan.txs()) {
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

    /** Every plausible (out, in) pair, best first: smallest gap, then IBAN-confirmed, then equal amounts. */
    static List<Edge> candidateEdges(List<Tx> txs, Rates rates) {
        record Key(String currency, long amount) {}
        var ins = new HashMap<Key, List<Tx>>();
        var insByDate = new TreeMap<LocalDate, List<Tx>>(); // for the other currencies, where amounts differ
        var currencies = new HashSet<String>();
        for (Tx t : txs) {
            currencies.add(t.currency());
            if (t.amountMinor() > 0) {
                ins.computeIfAbsent(new Key(t.currency(), t.amountMinor()), k -> new ArrayList<>()).add(t);
                insByDate.computeIfAbsent(t.date(), k -> new ArrayList<>()).add(t);
            }
        }
        var edges = new ArrayList<Edge>();
        for (Tx out : txs) {
            if (out.amountMinor() >= 0) {
                continue;
            }
            var partners = new ArrayList<>(ins.getOrDefault(new Key(out.currency(), -out.amountMinor()), List.of()));
            if (currencies.size() > 1) { // 3 business days are at most 7 calendar days
                insByDate.subMap(out.date().minusDays(7), true, out.date().plusDays(7), true).values()
                        .forEach(day -> day.stream().filter(in -> !in.currency().equals(out.currency()))
                                .forEach(partners::add));
            }
            for (Tx in : partners) {
                Edge e = edge(out, in, false, rates);
                if (e != null) {
                    edges.add(e);
                }
            }
        }
        edges.sort(Comparator.comparingInt(Edge::businessDays).thenComparing(Edge::iban, Comparator.reverseOrder())
                .thenComparing(Edge::fx).thenComparingLong(e -> e.out().id()).thenComparingLong(e -> e.in().id()));
        return edges;
    }

    /**
     * The pair (out, in) if it is a possible transfer, otherwise null: two different accounts, no IBAN naming a third
     * one, at most {@value #MAX_BUSINESS_DAYS} business days apart, and
     * <ul>
     *   <li>one currency: opposite equal amounts;</li>
     *   <li>two currencies (CP6.19): the amounts within the user's rate for the pair, when there is one; without a rate,
     *       only an IBAN naming the other account makes it a transfer (amounts that differ prove nothing alone).</li>
     * </ul>
     */
    static Edge edge(Tx out, Tx in, boolean user, Rates rates) {
        if (out.amountMinor() >= 0 || in.amountMinor() <= 0 || in.accountId() == out.accountId()
                || (out.namedAccount() != null && out.namedAccount() != in.accountId())
                || (in.namedAccount() != null && in.namedAccount() != out.accountId())) {
            return null;
        }
        boolean iban = out.namedAccount() != null || in.namedAccount() != null;
        boolean fx = !in.currency().equals(out.currency());
        if (!fx && in.amountMinor() != -out.amountMinor()) {
            return null;
        }
        if (fx) {
            Boolean close = rates.close(out, in);
            if (close == null ? !iban : !close) {
                return null;
            }
        }
        int gap = businessDays(out.date(), in.date());
        return gap <= MAX_BUSINESS_DAYS ? new Edge(out, in, gap, iban, fx, user) : null;
    }

    /**
     * Greedy matching, one quality level (gap, IBAN, equal amounts) at a time. Within a level, a transaction with more than one free
     * partner is a tie: it and its partners at that level stay unpaired (DESIGN: "ties go to review"), and the edges
     * between them go to {@code tied}.
     */
    static List<Edge> choose(List<Edge> edges, Set<Long> used, List<Edge> tied) {
        var chosen = new ArrayList<Edge>();
        var blocked = new HashSet<Long>();
        int i = 0;
        while (i < edges.size()) {
            int j = i;
            while (j < edges.size() && edges.get(j).businessDays() == edges.get(i).businessDays()
                    && edges.get(j).iban() == edges.get(i).iban() && edges.get(j).fx() == edges.get(i).fx()) {
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
                    tied.add(e);
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
                INSERT INTO transfer_pair (household_id, out_transaction_id, in_transaction_id, method, business_days, source)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                HOUSEHOLD, e.out().id(), e.in().id(), method(e), e.businessDays(), source(e));
        for (Tx t : List.of(e.out(), e.in())) {
            long other = t == e.out() ? e.in().accountId() : e.out().accountId();
            jdbc.update("UPDATE transaction SET transfer_pair_id = ?, transfer_state = 'PAIRED', transfer_account_id = ? WHERE id = ?",
                    pairId, other, t.id());
            categorizeAsTransfer(t);
        }
    }

    private static String method(Edge e) {
        return e.iban() ? "IBAN" : e.fx() ? "FX_RATE" : "AMOUNT_DATE";
    }

    private static String source(Edge e) {
        return e.user() ? "USER" : "AUTO";
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

    /**
     * One "Which transfer is this?" question: a transaction and its equally good partners, closest first. A group of
     * tied transactions is asked about once, through the one with the most partners (then money out, then the oldest
     * id); answering it can settle the rest of the group.
     */
    public record Tie(long transactionId, boolean outgoing, List<Long> options) {}

    /** The open questions, by transaction id. */
    public List<Tie> ties() {
        var partners = new HashMap<Long, List<Edge>>();
        var parent = new HashMap<Long, Long>();
        for (Edge e : plan().tied()) {
            partners.computeIfAbsent(e.out().id(), k -> new ArrayList<>()).add(e);
            partners.computeIfAbsent(e.in().id(), k -> new ArrayList<>()).add(e);
            parent.put(root(parent, e.out().id()), root(parent, e.in().id()));
        }
        Comparator<Long> asked = Comparator.<Long>comparingInt(id -> partners.get(id).size()).reversed()
                .thenComparing(id -> !outgoing(partners.get(id), id))
                .thenComparing(Comparator.naturalOrder());
        var anchors = new HashMap<Long, Long>(); // group → the transaction asked about
        for (long id : partners.keySet()) {
            anchors.merge(root(parent, id), id, (a, b) -> asked.compare(a, b) <= 0 ? a : b);
        }
        return anchors.values().stream().sorted().map(id -> {
            boolean outgoing = outgoing(partners.get(id), id);
            List<Long> options = partners.get(id).stream()
                    .sorted(Comparator.comparingInt(Edge::businessDays)
                            .thenComparing(e -> (outgoing ? e.in() : e.out()).date())
                            .thenComparingLong(e -> (outgoing ? e.in() : e.out()).id()))
                    .map(e -> (outgoing ? e.in() : e.out()).id())
                    .toList();
            return new Tie(id, outgoing, options);
        }).toList();
    }

    private static boolean outgoing(List<Edge> edges, long id) {
        return edges.getFirst().out().id() == id;
    }

    private static long root(Map<Long, Long> parent, long id) {
        long r = id;
        while (parent.containsKey(r) && parent.get(r) != r) {
            r = parent.get(r);
        }
        return r;
    }

    /** Thrown when an answer names a transaction the question did not offer. */
    public static class NotAnOptionException extends IllegalArgumentException {
        NotAnOptionException(String message) {
            super(message);
        }
    }

    /**
     * The answer to a tie question: {@code pairWith} is the partner the user picked, or null for "none of these" (none
     * of the offered pairs ever forms). Kept for good; the caller reruns the pipeline.
     */
    @Transactional
    public void decide(long transactionId, Long pairWith) {
        Tie tie = ties().stream().filter(t -> t.transactionId() == transactionId).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No transfer question for transaction " + transactionId));
        if (pairWith != null && !tie.options().contains(pairWith)) {
            throw new NotAnOptionException("Transaction " + pairWith + " is not one of the offered transfers");
        }
        for (long other : pairWith != null ? List.of(pairWith) : tie.options()) {
            jdbc.update("""
                    INSERT INTO transfer_decision (household_id, out_transaction_id, in_transaction_id, decision)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT transfer_decision_pair_uq DO UPDATE SET decision = EXCLUDED.decision""",
                    HOUSEHOLD, tie.outgoing() ? transactionId : other, tie.outgoing() ? other : transactionId,
                    pairWith != null ? "SAME" : "DIFFERENT");
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
