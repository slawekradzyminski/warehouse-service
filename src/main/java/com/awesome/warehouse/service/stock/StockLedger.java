package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.repository.stock.StockItemRepository;
import com.awesome.warehouse.repository.stock.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The ledger primitives every stock change goes through, inside the caller's transaction:
 * items are locked in ascending product id order (so concurrent multi-product changes cannot
 * deadlock), each change writes one movement, increments the item's {@code ledgerVersion} and
 * queues the new level for the backend projection ({@link StockLevelOutbox}).
 *
 * <p>Timestamps are zone-less UTC date-times, as the backend's movements were.
 */
@Component
@RequiredArgsConstructor
public class StockLedger {

    /** Actor of purchase receipts and return restocks, as the backend recorded them. */
    public static final String RECEIPT_ACTOR = "warehouse-service";
    public static final String MAXIMUM_EXCEEDED = "Inventory quantity exceeds supported maximum";
    static final String INITIAL_STOCK_REASON = "Initial stock";

    private final StockItemRepository items;
    private final StockMovementRepository movements;
    private final StockLevelOutbox levelOutbox;
    private final StockReadiness readiness;
    private final Clock warehouseClock;

    /** What a ledger change records besides the quantity. */
    public record Change(InventoryMovementType type, int delta, Long orderId, UUID reservationId, String actor,
                         String reason, UUID requestId) {
    }

    /** Locks the items of the given products in ascending id order; products without an item are absent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, StockItemEntity> lockInOrder(Collection<Long> productIds) {
        Map<Long, StockItemEntity> locked = new LinkedHashMap<>();
        for (Long productId : new TreeSet<>(productIds)) {
            items.findForUpdate(productId).ifPresent(item -> locked.put(productId, item));
        }
        return locked;
    }

    /**
     * Applies a change to a locked item. Callers check the bounds first (see {@link #resulting});
     * a change that would leave the item outside 0..{@link Integer#MAX_VALUE} is a programming error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovementEntity record(StockItemEntity item, Change change) {
        long after = resulting(item, change.delta());
        if (after < 0 || after > Integer.MAX_VALUE) {
            throw new IllegalStateException("Ledger change out of bounds for product " + item.getProductId());
        }
        LocalDateTime now = now();
        item.setQuantity((int) after);
        item.setLedgerVersion(item.getLedgerVersion() + 1);
        item.setUpdatedAt(now);
        items.save(item);
        StockMovementEntity movement = movements.save(StockMovementEntity.builder()
                .productId(item.getProductId())
                .orderId(change.orderId())
                .reservationId(change.reservationId())
                .type(change.type())
                .delta(change.delta())
                .quantityAfter((int) after)
                .actor(change.actor())
                .reason(change.reason())
                .requestId(change.requestId())
                .ledgerVersion(item.getLedgerVersion())
                .createdAt(now)
                .build());
        levelOutbox.enqueue(item);
        return movement;
    }

    /**
     * Creates the item of a new product with {@code ledgerVersion} 1 and, for a positive quantity,
     * an {@code INITIAL_STOCK} movement. The insert is flushed, so a concurrent create of the same
     * product fails here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockItemEntity create(Long productId, int quantity, String actor) {
        LocalDateTime now = now();
        StockItemEntity item = items.saveAndFlush(new StockItemEntity(productId, quantity, 1, now));
        if (quantity > 0) {
            movements.save(StockMovementEntity.builder()
                    .productId(productId)
                    .type(InventoryMovementType.INITIAL_STOCK)
                    .delta(quantity)
                    .quantityAfter(quantity)
                    .actor(actor)
                    .reason(INITIAL_STOCK_REASON)
                    .ledgerVersion(1L)
                    .createdAt(now)
                    .build());
        }
        levelOutbox.enqueue(item);
        return item;
    }

    /**
     * Adds received goods (purchase receipts and resellable returns), the local successor of the
     * backend's {@code POST /internal/v1/inventory/receipts}: the same actor, reason, request id
     * and order id, and the same errors (404 "Product N not found", 409 on overflow).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void receive(Map<Long, Integer> quantities, InventoryMovementType type, Long orderId, String reason,
                        UUID requestId) {
        if (type != InventoryMovementType.PURCHASE_RECEIVED && type != InventoryMovementType.RETURN_RESTOCKED) {
            throw new IllegalArgumentException("Unsupported receipt movement type: " + type);
        }
        readiness.requireReady();
        Map<Long, Integer> ordered = new TreeMap<>(quantities);
        Map<Long, StockItemEntity> locked = lockInOrder(ordered.keySet());
        ordered.forEach((productId, quantity) -> {
            if (quantity == null || quantity <= 0) {
                throw new CustomException("Received quantity must be positive", HttpStatus.BAD_REQUEST);
            }
            StockItemEntity item = locked.get(productId);
            if (item == null) {
                throw new CustomException("Product " + productId + " not found", HttpStatus.NOT_FOUND);
            }
            if (resulting(item, quantity) > Integer.MAX_VALUE) {
                throw new CustomException(MAXIMUM_EXCEEDED, HttpStatus.CONFLICT);
            }
        });
        ordered.forEach((productId, quantity) -> record(locked.get(productId),
                new Change(type, quantity, orderId, null, RECEIPT_ACTOR, reason, requestId)));
    }

    /** The item's quantity after a change, without overflow. */
    static long resulting(StockItemEntity item, int delta) {
        return (long) item.getQuantity() + delta;
    }

    /** The ledger's clock: a zone-less UTC date-time with microsecond precision. */
    public LocalDateTime now() {
        return LocalDateTime.now(warehouseClock.withZone(ZoneOffset.UTC));
    }
}
