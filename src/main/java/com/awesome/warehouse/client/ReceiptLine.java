package com.awesome.warehouse.client;

/** One product line of {@link InventoryReceiptRequest}. */
public record ReceiptLine(Long productId, int quantity) {
}
