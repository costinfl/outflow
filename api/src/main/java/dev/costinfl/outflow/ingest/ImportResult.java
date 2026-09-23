package dev.costinfl.outflow.ingest;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What one file import did, in the plain numbers the import summary shows.
 *
 * @param duplicateFile   the exact same file was already imported for this account; nothing was stored
 * @param rows            data rows in the file
 * @param newTransactions transactions created by this file
 * @param alreadyImported rows that matched an existing transaction (overlap with an earlier upload)
 */
public record ImportResult(
        long accountId,
        Optional<Long> statementFileId,
        String parserId,
        boolean duplicateFile,
        int rows,
        int newTransactions,
        int alreadyImported,
        Optional<LocalDate> periodFrom,
        Optional<LocalDate> periodTo) {}
