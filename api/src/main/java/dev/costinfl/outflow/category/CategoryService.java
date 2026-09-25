package dev.costinfl.outflow.category;

import dev.costinfl.outflow.category.CategoryResolver.Resolution;
import dev.costinfl.outflow.category.CategoryRule.Direction;
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
                SELECT source, priority, match_type, pattern, category_id, direction FROM category_rule WHERE household_id = ?""",
                (rs, i) -> new CategoryRule(CategoryRule.Source.valueOf(rs.getString(1)), rs.getInt(2),
                        CategoryRule.MatchType.valueOf(rs.getString(3)), rs.getString(4), rs.getLong(5),
                        rs.getString(6) == null ? null : Direction.valueOf(rs.getString(6))),
                HOUSEHOLD));
    }

    /**
     * Recomputes the category of every automation-categorized or uncategorized transaction from the current rules
     * and learned merchant categories, money sent and money received each by the rules for its direction. Paired and
     * provisional transfers keep their SYSTEM category (TransferService). Writes only rows that change; returns how
     * many.
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
            for (Direction money : Direction.values()) {
                Optional<Resolution> r = resolver.resolve(m.key(), m.learned(), money);
                boolean sent = money == Direction.OUT;
                changed += r.isPresent()
                        ? jdbc.update("""
                                UPDATE transaction SET category_id = ?, category_source = ?, category_confidence = ?
                                WHERE merchant_id = ? AND (amount_minor < 0) = ?
                                  AND category_source IS DISTINCT FROM 'USER' AND transfer_state IS NULL
                                  AND (category_id, category_source, category_confidence)
                                      IS DISTINCT FROM (?::bigint, ?::text, ?::numeric)""",
                                r.get().categoryId(), r.get().source().name(), r.get().confidence(), m.id(), sent,
                                r.get().categoryId(), r.get().source().name(), r.get().confidence())
                        : jdbc.update("""
                                UPDATE transaction SET category_id = NULL, category_source = NULL, category_confidence = NULL
                                WHERE merchant_id = ? AND (amount_minor < 0) = ?
                                  AND category_source IS DISTINCT FROM 'USER' AND transfer_state IS NULL
                                  AND category_id IS NOT NULL""",
                                m.id(), sent);
            }
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
            putMerchantRule(merchantId, categoryId, null);
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

    /**
     * "Pick category (applies to all)" from the review inbox: a tier-1 rule for the merchant, then every non-USER
     * transaction of the merchant follows it. Manual exceptions stay. Returns how many transactions changed.
     *
     * @param direction only money sent or only money received; null for both
     */
    @Transactional
    public int setMerchantCategory(long merchantId, long categoryId, Direction direction) {
        putMerchantRule(merchantId, categoryId, direction);
        return categorizeAll();
    }

    /**
     * The user's rule for a merchant, replacing any earlier one for the same money. A rule for one direction splits
     * an earlier rule for both: the other direction keeps its category.
     */
    private void putMerchantRule(long merchantId, long categoryId, Direction direction) {
        String key = jdbc.queryForObject("SELECT key FROM merchant WHERE id = ?", String.class, merchantId);
        String userRule = "household_id = ? AND source = 'USER' AND match_type = 'MERCHANT' AND pattern = ?";
        if (direction == null) {
            jdbc.update("DELETE FROM category_rule WHERE " + userRule, HOUSEHOLD, key);
        } else {
            var both = jdbc.queryForList("SELECT category_id FROM category_rule WHERE " + userRule + " AND direction IS NULL",
                    Long.class, HOUSEHOLD, key);
            jdbc.update("DELETE FROM category_rule WHERE " + userRule + " AND (direction IS NULL OR direction = ?)",
                    HOUSEHOLD, key, direction.name());
            Direction other = direction == Direction.IN ? Direction.OUT : Direction.IN;
            both.forEach(c -> insertRule(key, c, other));
        }
        insertRule(key, categoryId, direction);
    }

    private void insertRule(String key, long categoryId, Direction direction) {
        jdbc.update("""
                INSERT INTO category_rule (household_id, source, priority, match_type, pattern, category_id, direction)
                VALUES (?, 'USER', 10, 'MERCHANT', ?, ?, ?)""", HOUSEHOLD, key, categoryId,
                direction == null ? null : direction.name());
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
