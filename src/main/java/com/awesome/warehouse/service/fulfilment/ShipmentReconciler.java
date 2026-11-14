package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.CorrelationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically aligns shipments with the backend orders: cancels shipments whose order the
 * customer cancelled, adopts orders released to the warehouse without a local shipment and
 * fills the lines of up to 100 shipments created before 1.5.0.
 * Cancellations normally arrive within seconds through {@code EventFeedPoller}; this run
 * (every ten minutes by default) is the safety net. A backend outage only skips the run.
 */
@Slf4j
@Component
public class ShipmentReconciler {

    private final ShipmentService shipmentService;
    private final LegacyShipmentLines legacyLines;
    private final boolean enabled;

    public ShipmentReconciler(ShipmentService shipmentService, LegacyShipmentLines legacyLines,
                              @Value("${warehouse.reconcile.enabled:true}") boolean enabled) {
        this.shipmentService = shipmentService;
        this.legacyLines = legacyLines;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${warehouse.reconcile.interval:PT10M}",
            initialDelayString = "${warehouse.reconcile.interval:PT10M}")
    public void scheduledReconcile() {
        if (enabled) {
            // Each run is its own unit of work in the logs and in the backend calls it makes.
            CorrelationContext.generate().run(this::reconcile);
        }
    }

    public void reconcile() {
        try {
            shipmentService.reconcileCancellations();
            shipmentService.adoptReleasedOrders();
            legacyLines.fillPendingBatch();
        } catch (CustomException e) {
            log.warn("Shipment reconciliation skipped: {}", e.getMessage());
        }
    }
}
