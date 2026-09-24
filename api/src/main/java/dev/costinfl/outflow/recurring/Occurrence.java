package dev.costinfl.outflow.recurring;

import java.time.LocalDate;

/** One outgoing charge fed to the detector; {@code amountMinor} is the money out, positive. */
public record Occurrence(long transactionId, LocalDate date, long amountMinor) {}
