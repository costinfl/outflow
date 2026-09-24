package dev.costinfl.outflow.merchant;

import dev.costinfl.outflow.merchant.normalize.Alias;
import dev.costinfl.outflow.merchant.normalize.MerchantNormalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns {@code transaction.merchant_id} from each transaction's counterparty when the parser named one, else its raw
 * description (pipeline stage H, first half).
 * Classify merchants, not transactions: a key is resolved once and every transaction with it shares the row.
 */
@Service
public class MerchantService {

    private final JdbcTemplate jdbc;

    public MerchantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The normalizer with the current alias table. Built per call: aliases are few and change rarely. */
    public MerchantNormalizer normalizer() {
        return new MerchantNormalizer(jdbc.query("SELECT match_type, pattern, merchant_key FROM merchant_alias",
                (rs, i) -> new Alias(Alias.MatchType.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3))));
    }

    /** Assigns a merchant to every transaction that has none yet (after an import). Returns how many were set. */
    @Transactional
    public int assignMissing() {
        return assign("SELECT id, coalesce(counterparty_raw, description_raw), merchant_id FROM transaction WHERE merchant_id IS NULL");
    }

    /**
     * Recomputes every transaction's merchant from its raw description, e.g. after an alias change. Only rows whose
     * merchant actually changes are written. Returns how many moved.
     */
    @Transactional
    public int reassignAll() {
        return assign("SELECT id, coalesce(counterparty_raw, description_raw), merchant_id FROM transaction");
    }

    private int assign(String select) {
        var normalizer = normalizer();
        var merchantIds = new HashMap<String, Long>();
        record Row(long id, String raw, Long merchantId) {}
        List<Row> rows = jdbc.query(select, (rs, i) -> new Row(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3)));
        int changed = 0;
        for (Row row : rows) {
            String key = normalizer.key(row.raw());
            long merchantId = merchantIds.computeIfAbsent(key, this::merchantId);
            if (row.merchantId() == null || row.merchantId() != merchantId) {
                jdbc.update("UPDATE transaction SET merchant_id = ? WHERE id = ?", merchantId, row.id());
                changed++;
            }
        }
        return changed;
    }

    /** The merchant row for a key, created on first sight with a default display name. */
    long merchantId(String key) {
        jdbc.update("INSERT INTO merchant (key, display_name) VALUES (?, ?) ON CONFLICT ON CONSTRAINT merchant_key_uq DO NOTHING",
                key, MerchantNormalizer.displayName(key));
        return jdbc.queryForObject("SELECT id FROM merchant WHERE key = ?", Long.class, key);
    }

    /** Merchant key → number of transactions, for tests and the debug view. */
    public Map<String, Long> transactionCountsByKey() {
        var counts = new java.util.TreeMap<String, Long>();
        jdbc.query("SELECT m.key, count(*) FROM transaction t JOIN merchant m ON m.id = t.merchant_id GROUP BY m.key",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                });
        return counts;
    }
}
