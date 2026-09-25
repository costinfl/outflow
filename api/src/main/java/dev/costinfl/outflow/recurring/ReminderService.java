package dev.costinfl.outflow.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Remind me before next charge" (DESIGN: Recurring payments, row actions). Nothing leaves the machine: a reminder is
 * a review-inbox card from N days before a confirmed payment's next expected charge until its due date, and the same
 * reminders can be downloaded as a calendar file for the phone's own calendar to ring.
 */
@Service
public class ReminderService {

    public static final int MAX_DAYS = 14;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReminderService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Thrown when a reminder does not fit the subscription (not a confirmed payment). */
    public static class ReminderException extends IllegalStateException {
        ReminderException(String message) {
            super(message);
        }
    }

    /** Sets or clears ({@code null}) the reminder of a confirmed recurring payment. */
    @Transactional
    public void set(long subscriptionId, Integer daysBefore) {
        if (daysBefore != null && (daysBefore < 1 || daysBefore > MAX_DAYS)) {
            throw new IllegalArgumentException("daysBefore must be 1–" + MAX_DAYS);
        }
        var row = jdbc.queryForList("SELECT state, direction FROM subscription WHERE id = ?", subscriptionId);
        if (row.isEmpty()) {
            throw new NoSuchElementException("No subscription " + subscriptionId);
        }
        if (!"CONFIRMED".equals(row.getFirst().get("state")) || !"OUT".equals(row.getFirst().get("direction"))) {
            throw new ReminderException("Reminders are for confirmed recurring payments");
        }
        jdbc.update("UPDATE subscription SET remind_days_before = ?, updated_at = now() WHERE id = ?", daysBefore,
                subscriptionId);
    }

    /** A reminder due today: the payment, its next expected charge and how many days away it is. */
    public record Due(long subscriptionId, long merchantId, String name, String currency, Cadence cadence,
            String amountKind, long expectedAmountMinor, LocalDate dueDate, int daysBefore) {}

    /** Reminders to show today: from {@code daysBefore} days before the next charge to its day, unless answered. */
    public List<Due> due() {
        LocalDate today = LocalDate.now(clock);
        return jdbc.query("""
                SELECT id, merchant_id, name, currency, cadence, amount_kind, expected_amount_minor, next_expected_date,
                       remind_days_before
                FROM subscription
                WHERE state = 'CONFIRMED' AND direction = 'OUT' AND remind_days_before IS NOT NULL
                  AND next_expected_date IS NOT NULL AND next_expected_date >= ?::date
                  AND next_expected_date - remind_days_before <= ?::date
                  AND (reminded_through IS NULL OR reminded_through < next_expected_date)
                ORDER BY next_expected_date, id""",
                (rs, i) -> new Due(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        Cadence.valueOf(rs.getString(5)), rs.getString(6), rs.getLong(7),
                        rs.getObject(8, LocalDate.class), rs.getInt(9)),
                today, today);
    }

    /** "Got it": this charge's reminder is answered; the next one comes back before the charge after it. */
    @Transactional
    public void acknowledge(long subscriptionId) {
        int changed = jdbc.update("""
                UPDATE subscription SET reminded_through = next_expected_date, updated_at = now()
                WHERE id = ? AND remind_days_before IS NOT NULL AND next_expected_date IS NOT NULL""", subscriptionId);
        if (changed == 0) {
            throw new NoSuchElementException("No reminder for subscription " + subscriptionId);
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Every reminder as an iCalendar file (RFC 5545): one all-day event per payment on its next expected charge,
     * repeating at its cadence, with an alarm the set number of days before. Amounts are in the text only.
     */
    public String calendar() {
        var ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//Outflow//Charge reminders//EN");
        line(ics, "CALSCALE:GREGORIAN");
        jdbc.query("""
                SELECT id, name, currency, cadence, anchor_day, anchor_month, expected_amount_minor, amount_kind,
                       next_expected_date, remind_days_before
                FROM subscription
                WHERE state = 'CONFIRMED' AND direction = 'OUT' AND remind_days_before IS NOT NULL
                  AND next_expected_date IS NOT NULL
                ORDER BY id""", rs -> {
            LocalDate next = rs.getObject("next_expected_date", LocalDate.class);
            Cadence cadence = Cadence.valueOf(rs.getString("cadence"));
            String amount = ("VARIABLE".equals(rs.getString("amount_kind")) ? "about " : "")
                    + String.format(Locale.ROOT, "%d.%02d %s", rs.getLong("expected_amount_minor") / 100,
                            rs.getLong("expected_amount_minor") % 100, rs.getString("currency"));
            String name = rs.getString("name");
            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:subscription-" + rs.getLong("id") + "@outflow.local");
            line(ics, "DTSTAMP:" + LocalDate.now(clock).format(DAY) + "T000000Z");
            line(ics, "DTSTART;VALUE=DATE:" + next.format(DAY));
            line(ics, "DTEND;VALUE=DATE:" + next.plusDays(1).format(DAY));
            line(ics, "SUMMARY:" + escape(name + " charges " + amount));
            line(ics, "RRULE:" + rule(cadence, (Integer) rs.getObject("anchor_day"), (Integer) rs.getObject("anchor_month"),
                    next));
            line(ics, "TRANSP:TRANSPARENT");
            line(ics, "BEGIN:VALARM");
            line(ics, "ACTION:DISPLAY");
            line(ics, "DESCRIPTION:" + escape(name + " charges " + amount));
            line(ics, "TRIGGER:-P" + rs.getInt("remind_days_before") + "D");
            line(ics, "END:VALARM");
            line(ics, "END:VEVENT");
        });
        line(ics, "END:VCALENDAR");
        return ics.toString();
    }

    /**
     * The repeat rule. A monthly day after the 28th falls on a short month's last day, as the app's own due dates do:
     * "the last of the 28th…30th that exists" (a plain BYMONTHDAY=30 would skip February).
     */
    static String rule(Cadence cadence, Integer anchorDay, Integer anchorMonth, LocalDate next) {
        return switch (cadence) {
            case DAILY -> "FREQ=DAILY";
            case WEEKLY -> "FREQ=WEEKLY;BYDAY=" + next.getDayOfWeek().name().substring(0, 2);
            case MONTHLY -> {
                int day = anchorDay != null ? anchorDay : next.getDayOfMonth();
                yield day <= 28 ? "FREQ=MONTHLY;BYMONTHDAY=" + day
                        : "FREQ=MONTHLY;BYMONTHDAY=" + java.util.stream.IntStream.rangeClosed(28, Math.min(day, 31))
                                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(",")) + ";BYSETPOS=-1";
            }
            case YEARLY -> "FREQ=YEARLY;BYMONTH=" + (anchorMonth != null ? anchorMonth : next.getMonthValue())
                    + ";BYMONTHDAY=" + (anchorDay != null ? anchorDay : next.getDayOfMonth());
        };
    }

    /** RFC 5545 text escaping. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    /** Content lines end in CRLF and are folded at 75 octets (continuations start with a space). */
    private static void line(StringBuilder ics, String line) {
        byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int start = 0, limit = 75;
        while (bytes.length - start > limit) {
            int end = start + limit;
            while ((bytes[end] & 0xC0) == 0x80) { // never split a UTF-8 character
                end--;
            }
            ics.append(new String(bytes, start, end - start, java.nio.charset.StandardCharsets.UTF_8)).append("\r\n ");
            start = end;
            limit = 74;
        }
        ics.append(new String(bytes, start, bytes.length - start, java.nio.charset.StandardCharsets.UTF_8)).append("\r\n");
    }
}
