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
                   t.category_source, t.category_confidence, t.transfer_state, ta.name AS transfer_account_name
            FROM transaction t
            JOIN merchant m ON m.id = t.merchant_id
            LEFT JOIN category c ON c.id = t.category_id
            LEFT JOIN account ta ON ta.id = t.transfer_account_id
            """;

    static final RowMapper<TransactionView> ROW = (rs, i) -> new TransactionView(
            rs.getLong("id"), rs.getLong("account_id"), rs.getObject("booking_date", java.time.LocalDate.class),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("display_name"),
            rs.getString("merchant_key"), Iban.maskAll(rs.getString("description_raw")),
            (Long) rs.getObject("category_id"), rs.getString("category_code"), rs.getString("category_source"),
            rs.getBigDecimal("category_confidence"), rs.getString("transfer_state"),
            rs.getString("transfer_account_name"));

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
        f.merchant().ifPresent(id -> {
            where.append(" AND t.merchant_id = ?");
            args.add(id);
        });
        f.search().ifPresent(q -> {
            var amount = amountMinor(q);
            if (amount.isPresent()) {
                where.append(" AND abs(t.amount_minor) = ?");
                args.add(amount.get());
            } else {
                where.append(" AND (m.display_name ILIKE ? OR m.key ILIKE ? OR t.description_raw ILIKE ?)");
                String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                args.addAll(List.of(like, like, like));
            }
        });
        return jdbc.query(SELECT + where + " ORDER BY t.booking_date DESC, t.id DESC", ROW, args.toArray());
    }

    /** "18.50", "18,50", "1.234,56", "1850" → minor units; anything else is a text search. */
    static Optional<Long> amountMinor(String q) {
        String s = q.replace(" ", "");
        if (!s.matches("\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?")) {
            return Optional.empty();
        }
        int sep = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
        boolean hasDecimals = sep >= 0 && s.length() - sep - 1 <= 2;
        String units = hasDecimals ? s.substring(0, sep).replaceAll("[.,]", "") : s.replaceAll("[.,]", "");
        String decimals = hasDecimals ? (s.substring(sep + 1) + "00").substring(0, 2) : "00";
        return Optional.of(Long.parseLong(units + decimals));
    }

    public Optional<TransactionView> find(long id) {
        return jdbc.query(SELECT + " WHERE t.id = ?", ROW, id).stream().findFirst();
    }
}
