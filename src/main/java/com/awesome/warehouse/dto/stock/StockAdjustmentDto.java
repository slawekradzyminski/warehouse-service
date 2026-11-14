package com.awesome.warehouse.dto.stock;

import com.awesome.warehouse.entity.stock.InventoryMovementType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** An {@link InventoryMovementDto} of an adjustment plus the item's ledger version after it. */
public record StockAdjustmentDto(
        Long id,
        Long productId,
        @Schema(types = {"integer", "null"}) Long orderId,
        InventoryMovementType type,
        Integer delta,
        Integer quantityAfter,
        String actor,
        String reason,
        @Schema(types = {"string", "null"}) UUID requestId,
        @Schema(type = "string", format = "local-date-time",
                description = "UTC date and time without an offset") LocalDateTime createdAt,
        @Schema(example = "43") long ledgerVersion) {
}
