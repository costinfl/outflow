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
 * @param reference   bank-provided transaction reference, when the format has one
 */
public record ParsedRow(
        int rowNo,
        Map<String, String> payload,
        LocalDate bookingDate,
        Optional<LocalDate> valueDate,
        long amountMinor,
        String currency,
        String description,
        Optional<String> reference) {

    public ParsedRow {
        if (rowNo < 1) {
            throw new IllegalArgumentException("rowNo is 1-based: " + rowNo);
        }
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
