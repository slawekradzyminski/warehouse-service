package com.awesome.warehouse.client;

import java.math.BigDecimal;

/** Product snapshot returned by {@code GET /internal/v1/products}. */
public record InternalProductDto(Long id, String name, String category, BigDecimal price, Integer stockQuantity) {
}
