package dev.costinfl.outflow.ingest.parse;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Result of parsing one file: which account it probably belongs to, the period it covers, and its rows in file order.
 * The period is the min/max booking date of the rows (empty when the file has no rows).
 */
public record ParsedStatement(
        String parserId,
        Optional<AccountHint> accountHint,
        Optional<LocalDate> periodFrom,
        Optional<LocalDate> periodTo,
        List<ParsedRow> rows) {

    public ParsedStatement {
        rows = List.copyOf(rows);
    }

    public static ParsedStatement of(String parserId, Optional<AccountHint> accountHint, List<ParsedRow> rows) {
        var from = rows.stream().map(ParsedRow::bookingDate).min(LocalDate::compareTo);
        var to = rows.stream().map(ParsedRow::bookingDate).max(LocalDate::compareTo);
        return new ParsedStatement(parserId, accountHint, from, to, rows);
    }
}
