package com.awesome.warehouse.client;

import java.util.List;

/** Response of {@code POST /internal/v1/inventory/receipts}. */
public record InventoryReceiptResponse(List<Long> movementIds) {

    public InventoryReceiptResponse {
        movementIds = movementIds == null ? List.of() : List.copyOf(movementIds);
    }
}
