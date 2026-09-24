package dev.costinfl.outflow.txn;

import java.time.YearMonth;
import java.util.Optional;

/**
 * What a number on the home screen drills to, plus the transactions screen's own filters.
 *
 * @param kind          SPEND or INCOME scope; empty = every transaction of the month
 * @param category      only this category
 * @param uncategorized only transactions without a category
 * @param merchant      only this merchant
 * @param search        merchant name / bank text contains it, or the amount equals it ("18.50", "18,50")
 */
public record TransactionFilter(
        YearMonth month,
        String currency,
        Optional<Kind> kind,
        Optional<Long> category,
        boolean uncategorized,
        Optional<Long> merchant,
        Optional<String> search) {

    public enum Kind { SPEND, INCOME }

    public static TransactionFilter month(YearMonth month, String currency) {
        return new TransactionFilter(month, currency, Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    public TransactionFilter spend() {
        return new TransactionFilter(month, currency, Optional.of(Kind.SPEND), category, uncategorized, merchant, search);
    }

    public TransactionFilter income() {
        return new TransactionFilter(month, currency, Optional.of(Kind.INCOME), category, uncategorized, merchant, search);
    }

    public TransactionFilter inCategory(long categoryId) {
        return new TransactionFilter(month, currency, kind, Optional.of(categoryId), false, merchant, search);
    }

    public TransactionFilter uncategorizedOnly() {
        return new TransactionFilter(month, currency, kind, Optional.empty(), true, merchant, search);
    }

    public TransactionFilter atMerchant(long merchantId) {
        return new TransactionFilter(month, currency, kind, category, uncategorized, Optional.of(merchantId), search);
    }

    public TransactionFilter matching(String text) {
        return new TransactionFilter(month, currency, kind, category, uncategorized, merchant,
                Optional.ofNullable(text).map(String::strip).filter(s -> !s.isEmpty()));
    }
}
