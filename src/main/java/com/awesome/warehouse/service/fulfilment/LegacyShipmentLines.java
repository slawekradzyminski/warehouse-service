package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentLineEntity;
import com.awesome.warehouse.repository.fulfilment.ShipmentLineRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.service.returns.OrderLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;

/**
 * Fills the lines of shipments created before 1.5.0. The V6 migration cannot derive them
 * (order snapshots live in the backend), so it marks those shipments {@code linesPending}.
 * Such a shipment was the order's only parcel, so its lines are the order's items merged by
 * product. They are filled on first read of the shipment and, in batches, by the
 * reconciler. Filling only adds derived rows; it never changes a shipment's state.
 *
 * <p>The snapshots are read outside any transaction; each order is then filled in its own
 * short transaction under the order lock, re-checking the flag, so concurrent readers fill
 * an order once. A backend outage leaves the flag set for the next attempt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyShipmentLines {

    static final int BATCH = 100;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentLineRepository lineRepository;
    private final BackendClient backendClient;
    private final OrderLocks orderLocks;
    private final TransactionTemplate transaction;

    /** Fills the pending parcels among {@code parcels}; call outside any transaction. */
    public void fill(Collection<ShipmentEntity> parcels) {
        List<Long> orderIds = parcels.stream().filter(ShipmentEntity::isLinesPending)
                .map(ShipmentEntity::getOrderId).distinct().limit(BATCH).toList();
        if (orderIds.isEmpty()) {
            return;
        }
        List<InternalOrderDto> orders;
        try {
            orders = backendClient.getOrders(orderIds);
        } catch (CustomException e) {
            log.debug("Legacy shipment lines not filled: {}", e.getMessage());
            return;
        }
        for (InternalOrderDto order : orders) {
            orderLocks.ensureExists(order.id());
            transaction.executeWithoutResult(status -> fillLocked(order));
        }
    }

    /** Fills one batch of pending parcels. @return the number of parcels that were pending */
    public int fillPendingBatch() {
        List<ShipmentEntity> pending = shipmentRepository.findByLinesPendingTrueOrderByIdAsc(PageRequest.of(0, BATCH));
        fill(pending);
        return pending.size();
    }

    private void fillLocked(InternalOrderDto order) {
        orderLocks.lockInTransaction(order.id());
        for (ShipmentEntity parcel : shipmentRepository.findByOrderIdOrderByIdAsc(order.id())) {
            if (!parcel.isLinesPending()) {
                continue;
            }
            for (ShipmentAllocator.Line line : WarehouseAllocation.lines(order)) {
                lineRepository.save(ShipmentLineEntity.builder()
                        .shipmentId(parcel.getId())
                        .productId(line.productId())
                        .productName(line.productName())
                        .category(line.category())
                        .quantity(line.quantity())
                        .build());
            }
            // A bulk update: derived rows are no state change, so version and updatedAt stay.
            shipmentRepository.markLinesFilled(parcel.getId());
            log.info("Filled the lines of shipment {} (order {}) from the order snapshot", parcel.getId(), order.id());
        }
    }
}
