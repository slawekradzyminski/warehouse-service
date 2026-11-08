package com.awesome.warehouse.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/inventory/receipts}. The backend books the
 * stock at most once per {@code requestId}; {@code orderId} is omitted for
 * purchase receipts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryReceiptRequest(
        UUID requestId,
        ReceiptType type,
        Long orderId,
        String reference,
        List<ReceiptLine> lines) {

    public InventoryReceiptRequest {
        lines = List.copyOf(lines);
    }
}
