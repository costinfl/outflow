package dev.costinfl.outflow.txn;

import java.time.YearMonth;
import java.util.Optional;

/**
 * What a number on the home screen drills to.
 *
 * @param kind     SPEND or INCOME scope; empty = every transaction of the month
 * @param category a category id; with {@code uncategorized} false. {@code uncategorized} true = only transactions
 *                 without a category
 */
public record TransactionFilter(
        YearMonth month, String currency, Optional<Kind> kind, Optional<Long> category, boolean uncategorized) {

    public enum Kind { SPEND, INCOME }

    public static TransactionFilter month(YearMonth month, String currency) {
        return new TransactionFilter(month, currency, Optional.empty(), Optional.empty(), false);
    }

    public TransactionFilter spend() {
        return new TransactionFilter(month, currency, Optional.of(Kind.SPEND), category, uncategorized);
    }

    public TransactionFilter income() {
        return new TransactionFilter(month, currency, Optional.of(Kind.INCOME), category, uncategorized);
    }

    public TransactionFilter inCategory(long categoryId) {
        return new TransactionFilter(month, currency, kind, Optional.of(categoryId), false);
    }

    public TransactionFilter uncategorizedOnly() {
        return new TransactionFilter(month, currency, kind, Optional.empty(), true);
    }
}
