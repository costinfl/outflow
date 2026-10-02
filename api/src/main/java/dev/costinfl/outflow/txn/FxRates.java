package dev.costinfl.outflow.txn;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The user's approximate exchange rates, one per currency pair (CP6.19, spec question 21): what lets a transfer from a
 * RON account to a EUR account be recognised by its amounts. Nothing is fetched: a rate source would send data off the
 * machine. A rate is only used to pair transfers, never to convert figures (those stay one currency at a time, CP6.17).
 *
 * <p>A pair is stored in alphabetical order, {@code EUR/RON} meaning "1 EUR = rate RON"; a rate given the other way round
 * is inverted.
 */
@Component
public class FxRates {

    static final long HOUSEHOLD = 1;
    static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("3");
    static final BigDecimal MAX_TOLERANCE = BigDecimal.TEN;
    private static final MathContext MATH = MathContext.DECIMAL64;

    /** 1 {@code base} = {@code rate} {@code quote}; amounts may be {@code tolerancePercent} away from it. */
    public record Rate(String base, String quote, BigDecimal rate, BigDecimal tolerancePercent) {

        /**
         * Whether money out of one currency and money in of the other are the same money at this rate: the rate the two
         * amounts imply is within the tolerance. Signs are ignored.
         */
        public boolean matches(long aMinor, String aCurrency, long bMinor, String bCurrency) {
            BigDecimal implied = implied(aMinor, aCurrency, bMinor, bCurrency);
            if (implied == null) {
                return false;
            }
            BigDecimal deviation = implied.subtract(rate).abs().divide(rate, MATH);
            return deviation.multiply(BigDecimal.valueOf(100)).compareTo(tolerancePercent) <= 0;
        }

        /** The rate (quote per base) the two amounts imply, or null when they are not this pair or one is zero. */
        public BigDecimal implied(long aMinor, String aCurrency, long bMinor, String bCurrency) {
            BigDecimal a = major(Math.abs(aMinor), aCurrency);
            BigDecimal b = major(Math.abs(bMinor), bCurrency);
            if (a.signum() == 0 || b.signum() == 0) {
                return null;
            }
            if (aCurrency.equals(base) && bCurrency.equals(quote)) {
                return b.divide(a, MATH);
            }
            if (aCurrency.equals(quote) && bCurrency.equals(base)) {
                return a.divide(b, MATH);
            }
            return null;
        }
    }

    /** One currency pair of the household's money, with the user's rate if set and the last transfer's rate if any. */
    public record PairView(String base, String quote, Rate rate, BigDecimal lastSeenRate, LocalDate lastSeenOn) {}

    private final JdbcTemplate jdbc;
    private final Currencies currencies;

    public FxRates(JdbcTemplate jdbc, Currencies currencies) {
        this.jdbc = jdbc;
        this.currencies = currencies;
    }

    /** The rates set, by {@link #key}. */
    public Map<String, Rate> all() {
        var rates = new HashMap<String, Rate>();
        jdbc.query("""
                SELECT base_currency, quote_currency, rate, tolerance_percent FROM fx_rate WHERE household_id = ?""", rs -> {
            var r = new Rate(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).stripTrailingZeros(),
                    rs.getBigDecimal(4).stripTrailingZeros());
            rates.put(key(r.base(), r.quote()), r);
        }, HOUSEHOLD);
        return rates;
    }

    /** The pair's key, whichever way round the two currencies are given. */
    static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a;
    }

    /**
     * Sets 1 {@code base} = {@code rate} {@code quote}, stored in alphabetical order (inverted when given the other way).
     *
     * @throws IllegalArgumentException for unknown or equal currencies, a rate that is not positive, or a tolerance
     *     outside 0–10%
     */
    public Rate set(String base, String quote, BigDecimal rate, BigDecimal tolerancePercent) {
        check(base);
        check(quote);
        if (base.equals(quote)) {
            throw new IllegalArgumentException("A rate needs two different currencies");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("The rate must be positive");
        }
        BigDecimal tolerance = tolerancePercent == null ? DEFAULT_TOLERANCE : tolerancePercent;
        if (tolerance.signum() < 0 || tolerance.compareTo(MAX_TOLERANCE) > 0) {
            throw new IllegalArgumentException("The tolerance must be between 0 and 10 percent");
        }
        if (tolerance.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("The tolerance has at most two decimals");
        }
        if (base.compareTo(quote) > 0) {
            rate = BigDecimal.ONE.divide(rate, MATH);
            String swap = base;
            base = quote;
            quote = swap;
        }
        rate = rate.setScale(10, RoundingMode.HALF_EVEN);
        if (rate.signum() <= 0 || rate.precision() - rate.scale() > 10) {
            throw new IllegalArgumentException("The rate is out of range");
        }
        jdbc.update("""
                INSERT INTO fx_rate (household_id, base_currency, quote_currency, rate, tolerance_percent)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT fx_rate_pair_uq DO UPDATE
                    SET rate = EXCLUDED.rate, tolerance_percent = EXCLUDED.tolerance_percent, updated_at = now()""",
                HOUSEHOLD, base, quote, rate, tolerance);
        return new Rate(base, quote, rate.stripTrailingZeros(), tolerance.stripTrailingZeros());
    }

    /** Removes the pair's rate; false when there was none. */
    public boolean delete(String a, String b) {
        check(a);
        check(b);
        String[] pair = key(a, b).split("/");
        return jdbc.update("DELETE FROM fx_rate WHERE household_id = ? AND base_currency = ? AND quote_currency = ?",
                HOUSEHOLD, pair[0], pair[1]) > 0;
    }

    /**
     * Every pair of the currencies the money is in, alphabetical, plus pairs with a rate but no money (yet): the user's
     * rate, and the rate of the latest paired transfer between the two currencies, as a hint.
     */
    public List<PairView> overview() {
        var inUse = currencies.inUse(List.of()).stream().sorted().toList();
        var rates = all();
        var keys = new java.util.TreeSet<String>(rates.keySet());
        for (int i = 0; i < inUse.size(); i++) {
            for (int j = i + 1; j < inUse.size(); j++) {
                keys.add(key(inUse.get(i), inUse.get(j)));
            }
        }
        var views = new ArrayList<PairView>();
        for (String k : keys) {
            String[] pair = k.split("/");
            var probe = new Rate(pair[0], pair[1], BigDecimal.ONE, BigDecimal.ZERO);
            var last = jdbc.query("""
                    SELECT o.amount_minor, o.currency, i.amount_minor, i.currency, greatest(o.booking_date, i.booking_date)
                    FROM transfer_pair p
                    JOIN transaction o ON o.id = p.out_transaction_id JOIN transaction i ON i.id = p.in_transaction_id
                    WHERE p.household_id = ? AND ((o.currency = ? AND i.currency = ?) OR (o.currency = ? AND i.currency = ?))
                    ORDER BY 5 DESC, p.id DESC LIMIT 1""", (rs, n) -> new PairView(pair[0], pair[1], rates.get(k),
                    probe.implied(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4))
                            .setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros(),
                    rs.getObject(5, LocalDate.class)), HOUSEHOLD, pair[0], pair[1], pair[1], pair[0]);
            views.add(last.isEmpty() ? new PairView(pair[0], pair[1], rates.get(k), null, null) : last.getFirst());
        }
        return views;
    }

    private static BigDecimal major(long minor, String currency) {
        return BigDecimal.valueOf(minor, Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits()));
    }

    private static void check(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO code like EUR");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency " + currency);
        }
    }
}
