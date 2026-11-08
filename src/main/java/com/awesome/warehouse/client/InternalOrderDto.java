package com.awesome.warehouse.client;

import com.awesome.warehouse.dto.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Order snapshot returned by {@code GET /internal/v1/orders/{id}}. */
public record InternalOrderDto(
        Long id,
        String username,
        OrderStatus status,
        FulfilmentState fulfilmentState,
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        String couponCode,
        InternalAddressDto shippingAddress,
        List<InternalOrderItemDto> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public InternalOrderDto {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int totalUnits() {
        return items.stream().mapToInt(InternalOrderItemDto::quantity).sum();
    }
}
