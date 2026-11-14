package com.awesome.warehouse.client;

import java.util.List;

/** Body of {@code POST /internal/v1/inventory/levels}. */
public record StockLevelsRequest(List<StockLevel> levels) {

    public StockLevelsRequest {
        levels = List.copyOf(levels);
    }
}
