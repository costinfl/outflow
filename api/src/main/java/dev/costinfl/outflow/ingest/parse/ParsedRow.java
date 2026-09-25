package dev.costinfl.outflow.ingest.parse;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One statement row: the raw cells exactly as read (stored as {@code raw_row.payload}) and the fields parsed from them.
 *
 * @param rowNo       1-based index among the file's data rows (header and preamble excluded)
 * @param payload     header → cell text, in file column order
 * @param amountMinor signed minor units; negative = money out
 * @param reference   bank-provided transaction reference that is unique per transaction, when the format has one
 * @param counterparty who was paid or who paid, when the format says so explicitly (e.g. ING's "Tranzactie la");
 *                    merchant detection prefers it over the full description
 * @param pending     the bank marks the row as not yet posted (e.g. a card reservation); it may reappear posted with
 *                    another date or amount (DESIGN: Pending vs posted)
 */
public record ParsedRow(
        int rowNo,
        Map<String, String> payload,
        LocalDate bookingDate,
        Optional<LocalDate> valueDate,
        long amountMinor,
        String currency,
        String description,
        Optional<String> reference,
        Optional<String> counterparty,
        boolean pending) {

    public ParsedRow(int rowNo, Map<String, String> payload, LocalDate bookingDate, Optional<LocalDate> valueDate,
            long amountMinor, String currency, String description, Optional<String> reference) {
        this(rowNo, payload, bookingDate, valueDate, amountMinor, currency, description, reference, Optional.empty());
    }

    public ParsedRow(int rowNo, Map<String, String> payload, LocalDate bookingDate, Optional<LocalDate> valueDate,
            long amountMinor, String currency, String description, Optional<String> reference,
            Optional<String> counterparty) {
        this(rowNo, payload, bookingDate, valueDate, amountMinor, currency, description, reference, counterparty, false);
    }

    public ParsedRow {
        if (rowNo < 1) {
            throw new IllegalArgumentException("rowNo is 1-based: " + rowNo);
        }
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
