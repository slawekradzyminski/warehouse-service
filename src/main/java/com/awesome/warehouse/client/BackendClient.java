package com.awesome.warehouse.client;

import com.awesome.warehouse.dto.order.PageDto;

import java.util.Collection;
import java.util.List;

/**
 * Backend internal API ({@code /internal/v1}). Implementations translate
 * backend 4xx responses into {@code CustomException}s with the same status and
 * message, and any unavailability into 503 "Backend unavailable".
 */
public interface BackendClient {

    InternalOrderDto getOrder(long orderId);

    /** PAID orders with fulfilment state NONE, oldest first. */
    PageDto<InternalOrderDto> fulfilmentQueue(int page, int size);

    /** Snapshots of the given orders in any status (unknown ids are skipped); at most 100 ids. */
    List<InternalOrderDto> getOrders(Collection<Long> ids);

    /** PAID orders already released to the warehouse (fulfilment state AWAITING_PICK), oldest first. */
    PageDto<InternalOrderDto> ordersAwaitingPick(int page, int size);

    InternalOrderDto updateFulfilment(long orderId, FulfilmentState state);

    InternalOrderDto warehouseCancel(long orderId);

    List<InternalProductDto> getProducts(Collection<Long> ids);

    List<InternalProductDto> getAllProducts();

    InventoryReceiptResponse receiveInventory(InventoryReceiptRequest request);

    void sendNotification(NotificationRequest request);

    /** Books a refund on a delivered order; idempotent per requestId. */
    InternalOrderDto refundOrder(long orderId, OrderRefundRequest request);

    /** The backend's access-token verification keys. */
    JwkSet jwks();
}
