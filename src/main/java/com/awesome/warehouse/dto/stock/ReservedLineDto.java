package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;

/** A reserved product with the quantity and ledger version its deduction left. */
@Schema(requiredProperties = {"productId", "quantityAfter", "ledgerVersion"})
public record ReservedLineDto(
        @Schema(example = "1") Long productId,
        @Schema(example = "8") int quantityAfter,
        @Schema(example = "42") long ledgerVersion) {
}
