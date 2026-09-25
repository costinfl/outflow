package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.category.CategoryRule.Direction;
import dev.costinfl.outflow.recurring.RecurringOverview.Coverage;
import dev.costinfl.outflow.recurring.RecurringOverview.Group;
import dev.costinfl.outflow.recurring.RecurringOverview.GroupKind;
import dev.costinfl.outflow.recurring.RecurringOverview.Item;
import dev.costinfl.outflow.recurring.RecurringOverview.Status;
import dev.costinfl.outflow.recurring.Subscription.State;
import dev.costinfl.outflow.txn.Slice;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Recurring payments screen (with recurring income as its own group) and the home "Committed every month" figure. Only CONFIRMED subscriptions (and, for a
 * past month, ones ENDED after it) count: a proposal is a question, not a commitment.
 */
@Service
public class RecurringService {

    /** Categories whose recurring payments are bills rather than subscriptions (DESIGN: grouping). */
    static final Set<String> BILLS = Set.of("UTILITIES", "TELECOM", "HOUSING", "FEES", "INSURANCE");
    static final int MONTHLY_HISTORY = 3;
    static final int YEARLY_HISTORY = 13;

    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;
    private final AlertService alerts;

    public RecurringService(JdbcTemplate jdbc, SubscriptionService subscriptions, AlertService alerts) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.alerts = alerts;
    }

    /**
     * Confirmed and ended subscriptions in {@code currency}. Without a month, confirmed ones count. With a month, those
     * that had started by its end are listed, and they count when confirmed or ended no earlier than its first day.
     */
    public RecurringOverview overview(Optional<YearMonth> month, String currency) {
        return overview(month, Slice.all(currency));
    }

    /** {@link #overview(Optional, String)} for the selected accounts only (the home accounts filter). */
    public RecurringOverview overview(Optional<YearMonth> month, Slice slice) {
        String currency = slice.currency();
        record Cat(Long id, String code, String name) {}
        var categories = new HashMap<Long, Cat>();
        jdbc.query("""
                SELECT DISTINCT ON (t.subscription_id) t.subscription_id, c.id, c.code, c.name
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.subscription_id IS NOT NULL
                ORDER BY t.subscription_id, t.booking_date DESC, t.id DESC""", rs -> {
            categories.put(rs.getLong(1), new Cat((Long) rs.getObject(2), rs.getString(3), rs.getString(4)));
        });

        var open = alerts.openBySubscription();
        var byGroup = new HashMap<GroupKind, List<Item>>();
        for (Subscription s : subscriptions.list()) {
            if (!s.currency().equals(currency) || !slice.includes(s.accountId())
                    || (s.state() != State.CONFIRMED && s.state() != State.ENDED)) {
                continue;
            }
            if (month.isPresent() && s.firstSeen().isAfter(month.get().atEndOfMonth())) {
                continue;
            }
            boolean counted = month.isEmpty() ? s.state() == State.CONFIRMED
                    : s.state() == State.CONFIRMED || !s.lastSeen().isBefore(month.get().atDay(1));
            Cat cat = categories.getOrDefault(s.id(), new Cat(null, null, null));
            var item = new Item(s.id(), s.name(), s.merchantId(), s.cadence(), s.amountKind(), s.expectedAmountMinor(),
                    s.cadence().monthlyMinor(s.expectedAmountMinor()), s.cadence().yearlyMinor(s.expectedAmountMinor()),
                    s.nextExpectedDate(), status(s, open.get(s.id())), counted, cat.id(),
                    cat.name());
            GroupKind kind = s.direction() == Direction.IN ? GroupKind.INCOME
                    : cat.code() != null && BILLS.contains(cat.code()) ? GroupKind.BILLS : GroupKind.SUBSCRIPTIONS;
            byGroup.computeIfAbsent(kind, k -> new ArrayList<>()).add(item);
        }

        var groups = new ArrayList<Group>();
        long monthly = 0, yearly = 0, income = 0;
        int counted = 0;
        for (GroupKind kind : GroupKind.values()) {
            List<Item> items = byGroup.get(kind);
            if (items == null) {
                continue;
            }
            items.sort(Comparator.comparing(Item::counted).reversed()
                    .thenComparing(Comparator.comparingLong(Item::monthlyMinor).reversed())
                    .thenComparing(Item::name));
            long groupMonthly = 0;
            for (Item i : items) {
                if (i.counted()) {
                    groupMonthly += i.monthlyMinor();
                    if (kind != GroupKind.INCOME) {
                        yearly += i.yearlyMinor();
                        counted++;
                    }
                }
            }
            if (kind == GroupKind.INCOME) {
                income = groupMonthly;
            } else {
                monthly += groupMonthly;
            }
            groups.add(new Group(kind, groupMonthly, items));
        }
        var propose = BigDecimal.valueOf(RecurrenceDetector.PROPOSE);
        int suggestions = (int) subscriptions.list().stream()
                .filter(s -> s.state() == State.PROPOSED && s.confidence().compareTo(propose) >= 0
                        && s.currency().equals(currency) && slice.includes(s.accountId()))
                .count();
        return new RecurringOverview(currency, month.map(YearMonth::toString).orElse(null), monthly, yearly, counted,
                groups, coverage().stream().filter(c -> slice.includes(c.accountId())).toList(), suggestions, income);
    }

    private static Status status(Subscription s, AlertService.Kind openAlert) {
        if (s.state() == State.ENDED) {
            return Status.ENDED;
        }
        if (openAlert == null) {
            return Status.ACTIVE;
        }
        return openAlert == AlertService.Kind.PRICE_CHANGE ? Status.PRICE_CHANGED : Status.MISSED;
    }

    /** Per account: first and last booking date, and which cadences that much history can detect. */
    public List<Coverage> coverage() {
        return jdbc.query("""
                SELECT a.id, a.name, min(t.booking_date), max(t.booking_date)
                FROM account a LEFT JOIN transaction t ON t.account_id = a.id
                GROUP BY a.id, a.name ORDER BY a.id""", (rs, i) -> {
            LocalDate from = rs.getObject(3, LocalDate.class);
            LocalDate to = rs.getObject(4, LocalDate.class);
            int months = from == null ? 0 : (int) ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
            return new Coverage(rs.getLong(1), rs.getString(2), from, to, months, months >= MONTHLY_HISTORY,
                    months >= YEARLY_HISTORY);
        });
    }
}
