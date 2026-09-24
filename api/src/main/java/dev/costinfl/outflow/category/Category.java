package dev.costinfl.outflow.category;

import io.swagger.v3.oas.annotations.media.Schema;

public record Category(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        Long parentId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "GROCERIES") String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Groceries") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Only SPEND counts towards spent")
        Kind kind) {

    public enum Kind { SPEND, INCOME, TRANSFER }
}
