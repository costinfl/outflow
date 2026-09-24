package dev.costinfl.outflow.ingest;

import dev.costinfl.outflow.ingest.account.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** The import summary screen's numbers (DESIGN: First-run flow, step 3). */
public record ImportSummary(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FileOutcome> files,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AccountImport> accounts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int newTransactions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int alreadyImported) {

    /** Totals for one account across the uploaded files. */
    public record AccountImport(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Account account,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Detected for the first time by this upload")
            boolean created,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int newTransactions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int alreadyImported,
            LocalDate periodFrom,
            LocalDate periodTo) {}
}
