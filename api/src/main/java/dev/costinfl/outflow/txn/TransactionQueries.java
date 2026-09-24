package dev.costinfl.outflow.txn;

import dev.costinfl.outflow.ingest.parse.Iban;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Read side for transactions. Grows the list/filter queries in M3. */
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

    public Optional<TransactionView> find(long id) {
        return jdbc.query(SELECT + " WHERE t.id = ?", ROW, id).stream().findFirst();
    }
}
