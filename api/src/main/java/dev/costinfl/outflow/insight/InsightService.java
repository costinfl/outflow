package dev.costinfl.outflow.insight;

import dev.costinfl.outflow.insight.MonthSummary.CategorySpend;
import dev.costinfl.outflow.insight.MonthSummary.Rest;
import dev.costinfl.outflow.txn.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Home-screen numbers. Every figure is a sum over a {@link Scope} predicate, so it drills to its transactions. */
@Service
public class InsightService {

    static final int TOP = 5;
    static final int BASELINE = 3;

    private static final String FROM = " FROM transaction t LEFT JOIN category c ON c.id = t.category_id ";

    private final JdbcTemplate jdbc;

    public InsightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<YearMonth> availableMonths(String currency) {
        return jdbc.queryForList("""
                SELECT DISTINCT to_char(booking_date, 'YYYY-MM') FROM transaction WHERE currency = ? ORDER BY 1""",
                String.class, currency).stream().map(YearMonth::parse).toList();
    }

    public MonthSummary month(YearMonth month, String currency) {
        var available = availableMonths(currency);

        long spent = sum(Scope.SPEND, month, currency, "-t.amount_minor");
        long income = sum(Scope.INCOME, month, currency, "t.amount_minor");

        var baseline = new ArrayList<YearMonth>();
        for (int i = 1; i <= BASELINE; i++) {
            if (available.contains(month.minusMonths(i))) {
                baseline.add(month.minusMonths(i));
            }
        }
        Long average = baseline.isEmpty() ? null
                : divide(baseline.stream().mapToLong(m -> sum(Scope.SPEND, m, currency, "-t.amount_minor")).sum(), baseline.size());

        var byCategory = spendByCategory(month, currency);
        var usual = new HashMap<Long, Long>(); // category id (0 = uncategorized) → summed over baseline
        for (YearMonth m : baseline) {
            spendByCategory(m, currency).forEach(r -> usual.merge(key(r.categoryId()), r.spentMinor(), Long::sum));
        }
        var ranked = byCategory.stream()
                .sorted(Comparator.comparingLong(CategorySpend::spentMinor).reversed().thenComparing(CategorySpend::name))
                .map(r -> {
                    long u = baseline.isEmpty() ? 0 : divide(usual.getOrDefault(key(r.categoryId()), 0L), baseline.size());
                    return new CategorySpend(r.categoryId(), r.code(), r.name(), r.spentMinor(), pct(r.spentMinor(), spent),
                            u, u > 0 ? deltaPct(r.spentMinor(), u) : null, r.transactionCount());
                })
                .toList();
        var top = ranked.stream().limit(TOP).toList();
        var restRows = ranked.stream().skip(TOP).toList();
        long restSpent = restRows.stream().mapToLong(CategorySpend::spentMinor).sum();

        var trust = jdbc.queryForMap("SELECT coalesce(sum(abs(t.amount_minor)), 0) AS total, "
                        + "coalesce(sum(abs(t.amount_minor) * coalesce(t.category_confidence, 0)), 0) AS weighted, "
                        + "coalesce(sum(abs(t.amount_minor)) FILTER (WHERE t.category_id IS NOT NULL), 0) AS categorized"
                        + FROM + "WHERE " + Scope.SPEND + " AND " + Scope.MONTH + " AND t.currency = ?",
                month.atDay(1), month.atDay(1), currency);
        BigDecimal total = new BigDecimal(trust.get("total").toString());
        var uncategorized = ranked.stream().filter(r -> r.categoryId() == null).findFirst();

        return new MonthSummary(
                month.toString(), currency, spent, baseline.size(), average,
                average == null || average == 0 ? null : deltaPct(spent, average),
                income, income - spent,
                ratioPct(new BigDecimal(trust.get("weighted").toString()), total),
                ratioPct(new BigDecimal(trust.get("categorized").toString()), total),
                uncategorized.map(CategorySpend::spentMinor).orElse(0L),
                uncategorized.map(CategorySpend::transactionCount).orElse(0),
                top, new Rest(restSpent, pct(restSpent, spent), restRows.size()),
                available.stream().map(YearMonth::toString).toList());
    }

    private long sum(String scope, YearMonth month, String currency, String expression) {
        return jdbc.queryForObject("SELECT coalesce(sum(" + expression + "), 0)" + FROM + "WHERE " + scope + " AND "
                + Scope.MONTH + " AND t.currency = ?", Long.class, month.atDay(1), month.atDay(1), currency);
    }

    private List<CategorySpend> spendByCategory(YearMonth month, String currency) {
        return jdbc.query("SELECT t.category_id, c.code, c.name, sum(-t.amount_minor) AS spent, count(*) AS n" + FROM
                        + "WHERE " + Scope.SPEND + " AND " + Scope.MONTH + " AND t.currency = ? "
                        + "GROUP BY t.category_id, c.code, c.name",
                (rs, i) -> new CategorySpend((Long) rs.getObject(1), rs.getString(2),
                        Objects.requireNonNullElse(rs.getString(3), "Uncategorized"), rs.getLong(4), 0, 0, null,
                        rs.getInt(5)),
                month.atDay(1), month.atDay(1), currency);
    }

    private static long key(Long categoryId) {
        return categoryId == null ? 0 : categoryId;
    }

    /** Integer division of minor units, rounded half up (never through floating point). */
    static long divide(long total, int n) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(n), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** (value − base) / base as a whole percentage, rounded half up. */
    static int deltaPct(long value, long base) {
        return BigDecimal.valueOf(value - base).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(base).abs(), 0, RoundingMode.HALF_UP).intValueExact();
    }

    /** part / whole as a whole percentage; 0 when there is no whole. */
    static int pct(long part, long whole) {
        return whole == 0 ? 0 : ratioPct(BigDecimal.valueOf(part), BigDecimal.valueOf(whole));
    }

    static int ratioPct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? 0
                : part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP).intValueExact();
    }
}
