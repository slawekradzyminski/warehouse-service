package com.awesome.warehouse.client;

import com.awesome.warehouse.entity.stock.InventoryMovementType;

import java.time.LocalDateTime;
import java.util.UUID;

/** A backend inventory movement; imported with its id and timestamp unchanged. */
public record ExportedMovement(
        Long id,
        Long orderId,
        InventoryMovementType type,
        int delta,
        int quantityAfter,
        String actor,
        String reason,
        UUID requestId,
        LocalDateTime createdAt) {
}
