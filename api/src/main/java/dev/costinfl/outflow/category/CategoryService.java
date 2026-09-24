package dev.costinfl.outflow.category;

import dev.costinfl.outflow.category.CategoryResolver.Resolution;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pipeline stage H, second half: classify merchants, then give every transaction its merchant's category, except
 * transactions whose category the user set by hand ({@code category_source = 'USER'}): those are never touched.
 */
@Service
public class CategoryService {

    public static final long HOUSEHOLD = 1;

    private final JdbcTemplate jdbc;

    public CategoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CategoryResolver resolver() {
        return new CategoryResolver(jdbc.query("""
                SELECT source, priority, match_type, pattern, category_id FROM category_rule WHERE household_id = ?""",
                (rs, i) -> new CategoryRule(CategoryRule.Source.valueOf(rs.getString(1)), rs.getInt(2),
                        CategoryRule.MatchType.valueOf(rs.getString(3)), rs.getString(4), rs.getLong(5)),
                HOUSEHOLD));
    }

    /**
     * Recomputes the category of every automation-categorized or uncategorized transaction from the current rules
     * and learned merchant categories. Writes only rows that change; returns how many.
     */
    @Transactional
    public int categorizeAll() {
        var resolver = resolver();
        record MerchantRow(long id, String key, Long learned) {}
        List<MerchantRow> merchants = jdbc.query(
                "SELECT id, key, default_category_id FROM merchant WHERE id IN (SELECT merchant_id FROM transaction)",
                (rs, i) -> new MerchantRow(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3)));
        int changed = 0;
        for (MerchantRow m : merchants) {
            Optional<Resolution> r = resolver.resolve(m.key(), m.learned());
            changed += r.isPresent()
                    ? jdbc.update("""
                            UPDATE transaction SET category_id = ?, category_source = ?, category_confidence = ?
                            WHERE merchant_id = ? AND category_source IS DISTINCT FROM 'USER'
                              AND (category_id, category_source, category_confidence)
                                  IS DISTINCT FROM (?::bigint, ?::text, ?::numeric)""",
                            r.get().categoryId(), r.get().source().name(), r.get().confidence(), m.id(),
                            r.get().categoryId(), r.get().source().name(), r.get().confidence())
                    : jdbc.update("""
                            UPDATE transaction SET category_id = NULL, category_source = NULL, category_confidence = NULL
                            WHERE merchant_id = ? AND category_source IS DISTINCT FROM 'USER' AND category_id IS NOT NULL""",
                            m.id());
        }
        return changed;
    }

    /** Minimum number of consistent manual edits before a merchant learns a category (tier 2). */
    static final int LEARN_AFTER = 2;

    /**
     * The user sets a category on one transaction (DESIGN: "one tap"). Without {@code applyToMerchant} only this
     * transaction changes and becomes {@code USER}. With it, a tier-1 rule for the merchant replaces any earlier one
     * and every non-USER transaction of the merchant follows it. Returns how many transactions changed in total.
     */
    @Transactional
    public int setCategory(long transactionId, long categoryId, boolean applyToMerchant) {
        long merchantId = jdbc.queryForObject("SELECT merchant_id FROM transaction WHERE id = ?", Long.class, transactionId);
        int changed;
        if (applyToMerchant) {
            String key = jdbc.queryForObject("SELECT key FROM merchant WHERE id = ?", String.class, merchantId);
            jdbc.update("DELETE FROM category_rule WHERE household_id = ? AND source = 'USER' AND match_type = 'MERCHANT' AND pattern = ?",
                    HOUSEHOLD, key);
            jdbc.update("""
                    INSERT INTO category_rule (household_id, source, priority, match_type, pattern, category_id)
                    VALUES (?, 'USER', 10, 'MERCHANT', ?, ?)""", HOUSEHOLD, key, categoryId);
            // This transaction now follows the rule (even if it was a manual exception before): it is the one the user
            // answered "apply to this merchant" on. Other manual exceptions stay as they are.
            changed = jdbc.update("""
                    UPDATE transaction SET category_id = ?, category_source = 'RULE', category_confidence = 1.00
                    WHERE id = ? AND (category_id, category_source) IS DISTINCT FROM (?::bigint, 'RULE')""",
                    categoryId, transactionId, categoryId);
        } else {
            changed = jdbc.update("""
                    UPDATE transaction SET category_id = ?, category_source = 'USER', category_confidence = 1.00
                    WHERE id = ? AND (category_id, category_source) IS DISTINCT FROM (?::bigint, 'USER')""",
                    categoryId, transactionId, categoryId);
        }
        relearn(merchantId);
        return changed + categorizeAll();
    }

    /** Undo a manual category: the transaction goes back to automatic categorization. Returns how many changed. */
    @Transactional
    public int clearManual(long transactionId) {
        long merchantId = jdbc.queryForObject("SELECT merchant_id FROM transaction WHERE id = ?", Long.class, transactionId);
        jdbc.update("""
                UPDATE transaction SET category_id = NULL, category_source = NULL, category_confidence = NULL
                WHERE id = ? AND category_source = 'USER'""", transactionId);
        relearn(merchantId);
        return categorizeAll();
    }

    /**
     * Tier 2 is derived from the user's own manual edits: a merchant learns a category once at least
     * {@link #LEARN_AFTER} of its transactions were set by hand, all to that same category. Any disagreement unlearns.
     */
    void relearn(long merchantId) {
        var manual = jdbc.queryForList(
                "SELECT category_id FROM transaction WHERE merchant_id = ? AND category_source = 'USER'", Long.class, merchantId);
        Long learned = manual.size() >= LEARN_AFTER && manual.stream().distinct().count() == 1 ? manual.getFirst() : null;
        jdbc.update("UPDATE merchant SET default_category_id = ? WHERE id = ?", learned, merchantId);
    }

    public List<Category> list() {
        return jdbc.query("SELECT id, parent_id, code, name, kind FROM category ORDER BY sort_order, id",
                (rs, i) -> new Category(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4),
                        Category.Kind.valueOf(rs.getString(5))));
    }

    public boolean exists(long categoryId) {
        return jdbc.queryForObject("SELECT count(*) FROM category WHERE id = ?", Long.class, categoryId) > 0;
    }

    public long categoryId(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }
}
