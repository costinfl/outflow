package dev.costinfl.outflow.ingest.account;

import io.swagger.v3.oas.annotations.media.Schema;

/** An account as the API shows it. Never carries the IBAN, only its masked form. */
public record Account(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "e.g. RO49 •••• 0000; absent when the IBAN is unknown") String ibanMasked,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "RON") String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Kind kind) {

    public enum Kind { CURRENT, SAVINGS, CARD }
}
