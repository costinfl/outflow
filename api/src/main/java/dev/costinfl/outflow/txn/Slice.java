package dev.costinfl.outflow.txn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which money a figure looks at: one currency, and some accounts or all of them (DESIGN: Home screen, "Accounts filter
 * at the top, all accounts by default"). Its SQL is {@link Scope#SLICE}; figures and their drill-through lists use the
 * same slice, so they stay equal under any filter.
 *
 * @param accounts empty = every account
 */
public record Slice(String currency, List<Long> accounts) {

    public Slice {
        accounts = accounts == null ? List.of() : List.copyOf(accounts.stream().distinct().sorted().toList());
    }

    public static Slice all(String currency) {
        return new Slice(currency, List.of());
    }

    public boolean includes(long accountId) {
        return accounts.isEmpty() || accounts.contains(accountId);
    }

    /** {@code leading} parameters followed by the three of {@link Scope#SLICE}. */
    public Object[] args(Object... leading) {
        Long[] ids = accounts.isEmpty() ? null : accounts.toArray(Long[]::new);
        var all = new ArrayList<Object>(Arrays.asList(leading));
        all.add(currency);
        all.add(ids);
        all.add(ids);
        return all.toArray();
    }
}
