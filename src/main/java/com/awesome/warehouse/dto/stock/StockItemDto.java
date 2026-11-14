package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;

/** A product's stock and ledger version. */
@Schema(requiredProperties = {"productId", "quantity", "ledgerVersion"})
public record StockItemDto(
        @Schema(example = "1") Long productId,
        @Schema(example = "10") int quantity,
        @Schema(example = "1") long ledgerVersion) {
}
