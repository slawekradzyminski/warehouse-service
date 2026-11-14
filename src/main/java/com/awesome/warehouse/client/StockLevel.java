package com.awesome.warehouse.client;

/** A product's stock as the ledger holds it; the backend applies it only when {@code ledgerVersion} is newer. */
public record StockLevel(Long productId, int quantity, long ledgerVersion) {
}
