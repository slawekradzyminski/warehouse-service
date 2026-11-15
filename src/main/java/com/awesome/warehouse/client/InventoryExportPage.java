package com.awesome.warehouse.client;

import java.util.List;

/**
 * One page of {@code GET /internal/v1/inventory/export}: items in product id order with their
 * movements; {@code nextAfterProductId} is null on the last page.
 */
public record InventoryExportPage(List<ExportedStockItem> items, Long nextAfterProductId) {

    public InventoryExportPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
