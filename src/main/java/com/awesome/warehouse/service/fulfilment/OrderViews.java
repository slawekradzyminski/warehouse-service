package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.InternalAddressDto;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.dto.order.AddressDto;
import com.awesome.warehouse.dto.order.OrderDto;
import com.awesome.warehouse.dto.order.OrderItemDto;
import com.awesome.warehouse.dto.order.PageDto;

import java.math.BigDecimal;

/** Maps backend order snapshots to the public order views the frontend already uses. */
final class OrderViews {

    private OrderViews() {
    }

    static PageDto<OrderDto> page(PageDto<InternalOrderDto> source) {
        PageDto<OrderDto> page = new PageDto<>();
        page.setContent(source.getContent() == null
                ? java.util.List.of()
                : source.getContent().stream().map(OrderViews::order).toList());
        page.setPageNumber(source.getPageNumber());
        page.setPageSize(source.getPageSize());
        page.setTotalElements(source.getTotalElements());
        page.setTotalPages(source.getTotalPages());
        return page;
    }

    static OrderDto order(InternalOrderDto order) {
        return OrderDto.builder()
                .id(order.id())
                .username(order.username())
                .items(order.items().stream().map(OrderViews::item).toList())
                .totalAmount(order.totalAmount())
                .subtotalAmount(order.subtotalAmount() == null ? order.totalAmount() : order.subtotalAmount())
                .discountAmount(order.discountAmount() == null ? BigDecimal.ZERO : order.discountAmount())
                .couponCode(order.couponCode())
                .status(order.status())
                .shippingAddress(address(order.shippingAddress()))
                .createdAt(order.createdAt())
                .updatedAt(order.updatedAt())
                .build();
    }

    static AddressDto address(InternalAddressDto address) {
        if (address == null) {
            return null;
        }
        return AddressDto.builder()
                .street(address.street())
                .city(address.city())
                .state(address.state())
                .zipCode(address.zipCode())
                .country(address.country())
                .build();
    }

    private static OrderItemDto item(InternalOrderItemDto item) {
        return OrderItemDto.builder()
                .id(item.id())
                .productId(item.productId())
                .productName(item.productName())
                .quantity(item.quantity())
                .unitPrice(item.unitPrice())
                .totalPrice(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .build();
    }
}
