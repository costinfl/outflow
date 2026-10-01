package dev.costinfl.outflow.txn;

import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The currencies the money is in (CP6.17, spec question 14): figures are one currency at a time, never converted, and
 * the user switches between them. Without a choice, the main currency of the selected accounts is shown: the one with
 * the most transactions, so an account kept in EUR shows its own money rather than an empty RON screen.
 */
@Component
public class Currencies {

    /** When there is no money at all yet. */
    public static final String FALLBACK = "RON";

    private final JdbcTemplate jdbc;

    public Currencies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Currencies with transactions in these accounts (empty = all), the most used first. */
    public List<String> inUse(List<Long> accounts) {
        Long[] ids = accounts == null || accounts.isEmpty() ? null : accounts.toArray(Long[]::new);
        return jdbc.queryForList("""
                SELECT t.currency FROM transaction t
                WHERE t.superseded_by IS NULL AND (?::bigint[] IS NULL OR t.account_id = ANY (?::bigint[]))
                GROUP BY t.currency ORDER BY count(*) DESC, t.currency""", String.class, ids, ids);
    }

    /**
     * The slice for a request: the given currency, or the accounts' main one when absent.
     *
     * @throws IllegalArgumentException when the currency is not an ISO 4217 code
     */
    public Slice slice(String currency, List<Long> accounts) {
        if (currency == null || currency.isBlank()) {
            return new Slice(inUse(accounts).stream().findFirst().orElse(FALLBACK), accounts);
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO code like RON");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency " + currency);
        }
        return new Slice(currency, accounts);
    }
}
