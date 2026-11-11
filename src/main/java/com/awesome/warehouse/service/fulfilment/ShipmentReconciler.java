package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.controller.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically aligns shipments with the backend orders: cancels shipments whose order the
 * customer cancelled, and adopts orders released to the warehouse without a local shipment.
 * Cancellations normally arrive within seconds through {@code EventFeedPoller}; this run
 * (every ten minutes by default) is the safety net. A backend outage only skips the run.
 */
@Slf4j
@Component
public class ShipmentReconciler {

    private final ShipmentService shipmentService;
    private final boolean enabled;

    public ShipmentReconciler(ShipmentService shipmentService,
                              @Value("${warehouse.reconcile.enabled:true}") boolean enabled) {
        this.shipmentService = shipmentService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${warehouse.reconcile.interval:PT10M}",
            initialDelayString = "${warehouse.reconcile.interval:PT10M}")
    public void scheduledReconcile() {
        if (enabled) {
            reconcile();
        }
    }

    public void reconcile() {
        try {
            shipmentService.reconcileCancellations();
            shipmentService.adoptReleasedOrders();
        } catch (CustomException e) {
            log.warn("Shipment reconciliation skipped: {}", e.getMessage());
        }
    }
}
