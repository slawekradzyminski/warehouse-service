package com.awesome.warehouse.client;

import com.awesome.warehouse.dto.order.PageDto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /**
     * Pushes ledger stock levels to the backend projection ({@code POST /internal/v1/inventory/levels});
     * the backend ignores levels whose version is not newer than its own.
     */
    StockLevelsResponse pushStockLevels(StockLevelsRequest request);

    /**
     * One page of the backend's former stock ledger for the one-time import
     * ({@code GET /internal/v1/inventory/export}); {@code afterProductId} null reads the first page.
     */
    InventoryExportPage exportInventory(Long afterProductId, int limit);

    /** The order saved for a checkout reservation, or empty when the backend has none (404). */
    Optional<Long> orderIdByReservation(UUID reservationId);

    void sendNotification(NotificationRequest request);

    /** Books a refund on a delivered order; idempotent per requestId. */
    InternalOrderDto refundOrder(long orderId, OrderRefundRequest request);

    /** The backend's access-token verification keys. */
    JwkSet jwks();

    /**
     * Domain events with a sequence greater than {@code after}, oldest first, at most
     * {@code limit} (1..200), restricted to the given wire type names.
     */
    EventFeedPage events(long after, int limit, Collection<String> types);
}
