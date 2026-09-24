package dev.costinfl.outflow.txn;

import dev.costinfl.outflow.ingest.parse.Iban;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Read side for transactions. */
@Component
public class TransactionQueries {

    static final String SELECT = """
            SELECT t.id, t.account_id, t.booking_date, t.amount_minor, t.currency, t.description_raw,
                   m.key AS merchant_key, m.display_name, t.category_id, c.code AS category_code,
                   t.category_source, t.category_confidence
            FROM transaction t
            JOIN merchant m ON m.id = t.merchant_id
            LEFT JOIN category c ON c.id = t.category_id
            """;

    static final RowMapper<TransactionView> ROW = (rs, i) -> new TransactionView(
            rs.getLong("id"), rs.getLong("account_id"), rs.getObject("booking_date", java.time.LocalDate.class),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("display_name"),
            rs.getString("merchant_key"), Iban.maskAll(rs.getString("description_raw")),
            (Long) rs.getObject("category_id"), rs.getString("category_code"), rs.getString("category_source"),
            rs.getBigDecimal("category_confidence"));

    private final JdbcTemplate jdbc;

    public TransactionQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The transactions behind a home-screen number, newest first. */
    public List<TransactionView> list(TransactionFilter f) {
        var where = new StringBuilder(" WHERE " + Scope.MONTH + " AND t.currency = ?");
        var args = new ArrayList<Object>(List.of(f.month().atDay(1), f.month().atDay(1), f.currency()));
        f.kind().ifPresent(k -> where.append(" AND ").append(k == TransactionFilter.Kind.SPEND ? Scope.SPEND : Scope.INCOME));
        f.category().ifPresent(id -> {
            where.append(" AND t.category_id = ?");
            args.add(id);
        });
        if (f.uncategorized()) {
            where.append(" AND t.category_id IS NULL");
        }
        return jdbc.query(SELECT + where + " ORDER BY t.booking_date DESC, t.id DESC", ROW, args.toArray());
    }

    public Optional<TransactionView> find(long id) {
        return jdbc.query(SELECT + " WHERE t.id = ?", ROW, id).stream().findFirst();
    }
}
