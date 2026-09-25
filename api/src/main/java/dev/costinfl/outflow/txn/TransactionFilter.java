package dev.costinfl.outflow.txn;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * What a number on the home screen drills to, plus the transactions screen's own filters.
 *
 * @param period        the month, or the 3-month window ending with it
 * @param kind          SPEND or INCOME scope; empty = every transaction of the period
 * @param category      only this category
 * @param uncategorized only transactions without a category
 * @param merchant      only this merchant
 * @param search        merchant name / bank text contains it, or the amount equals it ("18.50", "18,50")
 * @param slice         currency and accounts (the home accounts filter)
 */
public record TransactionFilter(
        Period period,
        Slice slice,
        Optional<Kind> kind,
        Optional<Long> category,
        boolean uncategorized,
        Optional<Long> merchant,
        Optional<String> search) {

    public enum Kind { SPEND, INCOME }

    public static TransactionFilter month(YearMonth month, String currency) {
        return month(month, Slice.all(currency));
    }

    public static TransactionFilter month(YearMonth month, Slice slice) {
        return new TransactionFilter(Period.month(month), slice, Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.empty());
    }

    public YearMonth month() {
        return period.last();
    }

    public String currency() {
        return slice.currency();
    }

    public TransactionFilter lastMonths(int months) {
        return new TransactionFilter(new Period(period.last(), months), slice, kind, category, uncategorized, merchant,
                search);
    }

    public TransactionFilter spend() {
        return new TransactionFilter(period, slice, Optional.of(Kind.SPEND), category, uncategorized, merchant, search);
    }

    public TransactionFilter income() {
        return new TransactionFilter(period, slice, Optional.of(Kind.INCOME), category, uncategorized, merchant, search);
    }

    public TransactionFilter inCategory(long categoryId) {
        return new TransactionFilter(period, slice, kind, Optional.of(categoryId), false, merchant, search);
    }

    public TransactionFilter uncategorizedOnly() {
        return new TransactionFilter(period, slice, kind, Optional.empty(), true, merchant, search);
    }

    public TransactionFilter atMerchant(long merchantId) {
        return new TransactionFilter(period, slice, kind, category, uncategorized, Optional.of(merchantId), search);
    }

    public TransactionFilter inAccounts(List<Long> accounts) {
        return new TransactionFilter(period, new Slice(slice.currency(), accounts), kind, category, uncategorized,
                merchant, search);
    }

    public TransactionFilter matching(String text) {
        return new TransactionFilter(period, slice, kind, category, uncategorized, merchant,
                Optional.ofNullable(text).map(String::strip).filter(s -> !s.isEmpty()));
    }
}
