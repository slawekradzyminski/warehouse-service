package com.awesome.warehouse.client;

import java.util.List;

/** A backend product's stock and its full movement history, for the one-time ledger import. */
public record ExportedStockItem(Long productId, int quantity, List<ExportedMovement> movements) {

    public ExportedStockItem {
        movements = movements == null ? List.of() : List.copyOf(movements);
    }
}
