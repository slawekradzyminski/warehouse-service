package com.awesome.warehouse.client;

import java.math.BigDecimal;

/**
 * Order line inside {@link InternalOrderDto}. {@code id} is the backend order
 * item id; it is optional in the contract and only used to keep the public
 * queue response identical to the backend's {@code OrderDto}.
 */
public record InternalOrderItemDto(
        Long id,
        Long productId,
        String productName,
        String category,
        Integer quantity,
        BigDecimal unitPrice) {
}
