package com.awesome.warehouse.dto.stock;

import com.awesome.warehouse.entity.stock.InventoryMovementType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One ledger movement, serialised exactly like the backend's former {@code InventoryMovementDto}:
 * the same field order, null fields written as null, {@code createdAt} a zone-less UTC date-time
 * with up to microsecond precision.
 */
public record InventoryMovementDto(
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
                description = "UTC date and time without an offset") LocalDateTime createdAt) {
}
