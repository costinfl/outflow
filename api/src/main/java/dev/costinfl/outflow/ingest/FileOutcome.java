package dev.costinfl.outflow.ingest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** What happened to one uploaded file. */
public record FileOutcome(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
        @Schema(description = "Why the file was not imported, in words for the user") String message,
        String parserId,
        Long accountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rows,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int newTransactions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Rows already imported by an earlier file")
        int alreadyImported,
        LocalDate periodFrom,
        LocalDate periodTo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Transactions this upload recognised as transfers between own accounts (excluded from spending)")
        int transfers,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Parser scores, best first; filled when the user has to pick (NEEDS_PARSER)")
        List<ParserCandidate> candidates) {

    public enum Status {
        /** Imported; some rows may have been known already. */
        IMPORTED,
        /** The exact same file was imported before for this account: nothing changed. */
        DUPLICATE_FILE,
        /** No parser recognised the file confidently: upload again with a parserId from the candidates. */
        NEEDS_PARSER,
        /** The file names no account: upload again with an accountId. */
        NEEDS_ACCOUNT,
        /** Unreadable or inconsistent: see message. Nothing from this file was stored. */
        FAILED
    }

    public record ParserCandidate(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String parserId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0..1") double score,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) {}

    static FileOutcome notImported(String fileName, Status status, String message, List<ParserCandidate> candidates) {
        return new FileOutcome(fileName, status, message, null, null, 0, 0, 0, null, null, 0, candidates);
    }
}
