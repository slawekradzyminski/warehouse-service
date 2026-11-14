package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.entity.stock.StockRestoreEntity;
import com.awesome.warehouse.repository.stock.StockRestoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Restores the stock of a cancelled order from the {@code stockRestore} of its
 * {@code order.cancelled} feed event (contract 3.1): one {@code ORDER_RESTORED} movement per
 * line with the order id, the event's actor and the reason "Order cancellation". A restore
 * happens at most once per order ({@code stock_restores}); events without {@code stockRestore}
 * (legacy rows, shipped orders) are ignored. Lines whose product has no item any more are
 * skipped with a warning.
 */
@Slf4j
@Component
public class StockRestoreConsumer {

    static final String ORDER_CANCELLATION = "Order cancellation";

    private final StockRestoreRepository restores;
    private final StockLedger ledger;
    private final StockReadiness readiness;
    private final TransactionTemplate transaction;

    public StockRestoreConsumer(StockRestoreRepository restores, StockLedger ledger, StockReadiness readiness,
                                PlatformTransactionManager transactionManager) {
        this.restores = restores;
        this.ledger = ledger;
        this.readiness = readiness;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The {@code stockRestore} of an event: who cancelled and the quantities per product. */
    public record StockRestore(long orderId, String actor, Map<Long, Integer> quantities) {

        public StockRestore {
            quantities = Map.copyOf(quantities);
        }
    }

    /**
     * Applies the event's restore in its own transaction.
     *
     * @return whether stock was restored by this call (false without {@code stockRestore} or when
     *         the order was restored before)
     * @throws IllegalArgumentException when {@code stockRestore} is malformed (a permanent failure)
     */
    public boolean restore(FeedEvent event) {
        Optional<StockRestore> restore = parse(event);
        if (restore.isEmpty()) {
            return false;
        }
        readiness.requireReady();
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> apply(event, restore.get())));
        } catch (DataIntegrityViolationException e) {
            log.info("Stock of order {} was restored concurrently", restore.get().orderId());
            return false;
        }
    }

    private boolean apply(FeedEvent event, StockRestore restore) {
        if (restores.existsById(restore.orderId())) {
            log.info("Stock of order {} was already restored; event {} ignored", restore.orderId(), event.id());
            return false;
        }
        Map<Long, StockItemEntity> locked = ledger.lockInOrder(restore.quantities().keySet());
        new TreeMap<>(restore.quantities()).forEach((productId, quantity) -> {
            StockItemEntity item = locked.get(productId);
            if (item == null) {
                log.warn("Order {}: product {} has no stock item; {} units not restored",
                        restore.orderId(), productId, quantity);
            } else if (StockLedger.resulting(item, quantity) > Integer.MAX_VALUE) {
                log.error("Order {}: restoring {} units of product {} would exceed the supported maximum",
                        restore.orderId(), quantity, productId);
            } else {
                ledger.record(item, new StockLedger.Change(InventoryMovementType.ORDER_RESTORED, quantity,
                        restore.orderId(), null, restore.actor(), ORDER_CANCELLATION, null));
            }
        });
        restores.saveAndFlush(new StockRestoreEntity(restore.orderId(), event.id(), ledger.now()));
        log.info("Stock of cancelled order {} restored ({} products)", restore.orderId(), restore.quantities().size());
        return true;
    }

    /**
     * Reads {@code data.stockRestore}: {@code {"actor":"alice","lines":[{"productId":1,"quantity":2}]}}.
     * Repeated products are summed.
     */
    static Optional<StockRestore> parse(FeedEvent event) {
        Object raw = event.data().get("stockRestore");
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof Map<?, ?> stockRestore)) {
            throw malformed(event, "stockRestore is not an object");
        }
        Long orderId = event.orderId() != null ? event.orderId()
                : event.data().get("orderId") instanceof Number number ? number.longValue() : null;
        if (orderId == null) {
            throw malformed(event, "no orderId");
        }
        if (!(stockRestore.get("actor") instanceof String actor) || actor.isBlank()) {
            throw malformed(event, "stockRestore.actor is missing");
        }
        if (!(stockRestore.get("lines") instanceof Collection<?> lines)) {
            throw malformed(event, "stockRestore.lines is missing");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Object entry : lines) {
            if (!(entry instanceof Map<?, ?> line) || !(line.get("productId") instanceof Number productId)
                    || !(line.get("quantity") instanceof Number quantity) || quantity.intValue() <= 0) {
                throw malformed(event, "invalid stockRestore line " + entry);
            }
            quantities.merge(productId.longValue(), quantity.intValue(), Math::addExact);
        }
        return Optional.of(new StockRestore(orderId, actor, quantities));
    }

    private static IllegalArgumentException malformed(FeedEvent event, String problem) {
        return new IllegalArgumentException("Event " + event.id() + ": " + problem);
    }
}
