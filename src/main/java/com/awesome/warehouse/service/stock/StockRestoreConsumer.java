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
import java.util.TreeMap;

/**
 * Restores the stock of a cancelled order from the backend's internal feed event
 * {@value #STOCK_RESTORE_REQUESTED} (contract 3.1), whose data is
 * {@code {"orderId":123,"actor":"alice","lines":[{"productId":1,"quantity":2}]}}: one
 * {@code ORDER_RESTORED} movement per line with the order id, the event's actor and the reason
 * "Order cancellation". A restore happens at most once per order ({@code stock_restores}, on top
 * of the feed's {@code processed_events}). {@code order.cancelled} never restores stock; the
 * backend records this separate event only for orders whose stock was deducted. Lines whose
 * product has no item any more are skipped with a warning.
 */
@Slf4j
@Component
public class StockRestoreConsumer {

    /** Wire type of the backend's internal restore event. */
    public static final String STOCK_RESTORE_REQUESTED = "stock.restore-requested";
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

    /** A requested restore: the order, who cancelled it and the quantities per product. */
    public record StockRestore(long orderId, String actor, Map<Long, Integer> quantities) {

        public StockRestore {
            quantities = Map.copyOf(quantities);
        }
    }

    /**
     * Applies a {@value #STOCK_RESTORE_REQUESTED} event in its own transaction; other event types
     * are ignored.
     *
     * @return whether stock was restored by this call (false for other event types or when the
     *         order was restored before)
     * @throws IllegalArgumentException when the event's data is malformed (a permanent failure)
     */
    public boolean restore(FeedEvent event) {
        if (!STOCK_RESTORE_REQUESTED.equals(event.type())) {
            return false;
        }
        StockRestore restore = parse(event);
        readiness.requireReady();
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> apply(event, restore)));
        } catch (DataIntegrityViolationException e) {
            log.info("Stock of order {} was restored concurrently", restore.orderId());
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
     * Reads the event's data {@code {"orderId":123,"actor":"alice","lines":[{"productId":1,"quantity":2}]}};
     * the event's own {@code orderId} is used when the data has none. Repeated products are summed.
     * A quantity below 1 or above {@link Integer#MAX_VALUE}, alone or summed, makes the event malformed.
     */
    static StockRestore parse(FeedEvent event) {
        Map<String, Object> data = event.data();
        Long orderId = event.orderId();
        if (data.get("orderId") instanceof Number number) {
            orderId = number.longValue();
        }
        if (orderId == null) {
            throw malformed(event, "no orderId");
        }
        if (!(data.get("actor") instanceof String actor) || actor.isBlank()) {
            throw malformed(event, "actor is missing");
        }
        if (!(data.get("lines") instanceof Collection<?> lines)) {
            throw malformed(event, "lines are missing");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Object entry : lines) {
            if (!(entry instanceof Map<?, ?> line) || !(line.get("productId") instanceof Number productId)
                    || !(line.get("quantity") instanceof Number quantity) || !inIntRange(quantity)) {
                throw malformed(event, "invalid line " + entry);
            }
            long total = (long) quantities.getOrDefault(productId.longValue(), 0) + quantity.intValue();
            if (total > Integer.MAX_VALUE) {
                // A poison event, not an arithmetic failure: the poller skips it instead of stalling on it.
                throw malformed(event, "quantity of product " + productId + " exceeds " + Integer.MAX_VALUE);
            }
            quantities.put(productId.longValue(), (int) total);
        }
        return new StockRestore(orderId, actor, quantities);
    }

    /** A positive quantity that fits an {@code int} (a long or big number is not silently truncated). */
    private static boolean inIntRange(Number quantity) {
        double units = quantity.doubleValue();
        return units >= 1 && units <= Integer.MAX_VALUE;
    }

    private static IllegalArgumentException malformed(FeedEvent event, String problem) {
        return new IllegalArgumentException("Event " + event.id() + ": " + problem);
    }
}
