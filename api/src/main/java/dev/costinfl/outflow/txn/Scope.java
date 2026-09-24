package dev.costinfl.outflow.txn;

/**
 * The SQL predicates that define every number on the home screen. Insight totals and the transaction list both use
 * these, so a figure always equals the sum of the transactions it drills to (plan: "Insight queries").
 * Aliases: {@code t} = transaction, {@code c} = its category (LEFT JOIN).
 */
public final class Scope {

    /**
     * Spent: every transaction in a SPEND category (refunds there reduce it), plus uncategorized money out.
     * Income and own-account transfers are never spending (DESIGN: Home screen design rules).
     */
    public static final String SPEND = "(c.kind = 'SPEND' OR (t.category_id IS NULL AND t.amount_minor < 0))";

    /** Income: transactions in INCOME categories. Uncategorized money in is not income until identified. */
    public static final String INCOME = "(c.kind = 'INCOME')";

    /** Calendar month by booking date: {@code ?} = first day of the month. */
    public static final String MONTH = "(t.booking_date >= ?::date AND t.booking_date < ?::date + interval '1 month')";

    private Scope() {}
}
