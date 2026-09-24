package dev.costinfl.outflow.recurring;

import dev.costinfl.outflow.recurring.RecurringOverview.Coverage;
import dev.costinfl.outflow.recurring.RecurringOverview.Group;
import dev.costinfl.outflow.recurring.RecurringOverview.GroupKind;
import dev.costinfl.outflow.recurring.RecurringOverview.Item;
import dev.costinfl.outflow.recurring.RecurringOverview.Status;
import dev.costinfl.outflow.recurring.Subscription.State;
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
 * The Recurring payments screen and the home "Committed every month" figure. Only CONFIRMED subscriptions (and, for a
 * past month, ones ENDED after it) count: a proposal is a question, not a commitment.
 */
@Service
public class RecurringService {

    /** Categories whose recurring payments are bills rather than subscriptions (DESIGN: grouping). */
    static final Set<String> BILLS = Set.of("UTILITIES", "TELECOM", "HOUSING", "FEES");
    static final int MONTHLY_HISTORY = 3;
    static final int YEARLY_HISTORY = 13;

    private final JdbcTemplate jdbc;
    private final SubscriptionService subscriptions;

    public RecurringService(JdbcTemplate jdbc, SubscriptionService subscriptions) {
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
    }

    /**
     * Confirmed and ended subscriptions in {@code currency}. Without a month, confirmed ones count. With a month, those
     * that had started by its end are listed, and they count when confirmed or ended no earlier than its first day.
     */
    public RecurringOverview overview(Optional<YearMonth> month, String currency) {
        record Cat(Long id, String code, String name) {}
        var categories = new HashMap<Long, Cat>();
        jdbc.query("""
                SELECT DISTINCT ON (t.subscription_id) t.subscription_id, c.id, c.code, c.name
                FROM transaction t LEFT JOIN category c ON c.id = t.category_id
                WHERE t.subscription_id IS NOT NULL
                ORDER BY t.subscription_id, t.booking_date DESC, t.id DESC""", rs -> {
            categories.put(rs.getLong(1), new Cat((Long) rs.getObject(2), rs.getString(3), rs.getString(4)));
        });

        var byGroup = new HashMap<GroupKind, List<Item>>();
        for (Subscription s : subscriptions.list()) {
            if (!s.currency().equals(currency) || (s.state() != State.CONFIRMED && s.state() != State.ENDED)) {
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
                    s.nextExpectedDate(), s.state() == State.ENDED ? Status.ENDED : Status.ACTIVE, counted, cat.id(),
                    cat.name());
            byGroup.computeIfAbsent(cat.code() != null && BILLS.contains(cat.code()) ? GroupKind.BILLS : GroupKind.SUBSCRIPTIONS,
                    k -> new ArrayList<>()).add(item);
        }

        var groups = new ArrayList<Group>();
        long monthly = 0, yearly = 0;
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
                    yearly += i.yearlyMinor();
                    counted++;
                }
            }
            monthly += groupMonthly;
            groups.add(new Group(kind, groupMonthly, items));
        }
        int suggestions = jdbc.queryForObject("SELECT count(*) FROM subscription WHERE state = 'PROPOSED' AND confidence >= ?",
                Integer.class, BigDecimal.valueOf(RecurrenceDetector.PROPOSE));
        return new RecurringOverview(currency, month.map(YearMonth::toString).orElse(null), monthly, yearly, counted,
                groups, coverage(), suggestions);
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
