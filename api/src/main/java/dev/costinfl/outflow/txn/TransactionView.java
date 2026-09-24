package dev.costinfl.outflow.txn;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A transaction as the UI shows it: merchant display name first, raw bank text (IBANs masked) on expand. */
public record TransactionView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long accountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate bookingDate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Signed minor units; negative = money out")
        long amountMinor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String merchantName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String merchantKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Bank text with IBANs masked")
        String description,
        Long categoryId,
        String categoryCode,
        @Schema(description = "USER, RULE, LEARNED, MCC, KEYWORD, SYSTEM; absent when uncategorized") String categorySource,
        BigDecimal categoryConfidence) {}
