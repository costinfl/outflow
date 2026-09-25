package dev.costinfl.outflow.recurring;

import java.time.LocalDate;

/** One charge (or payment received) fed to the detector; {@code amountMinor} is the money moved, positive. */
public record Occurrence(long transactionId, LocalDate date, long amountMinor) {}
