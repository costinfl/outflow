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

    public long categoryId(String code) {
        return jdbc.queryForObject("SELECT id FROM category WHERE code = ?", Long.class, code);
    }
}
