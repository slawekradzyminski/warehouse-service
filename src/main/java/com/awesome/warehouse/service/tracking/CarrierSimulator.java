package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.CorrelationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The simulated carrier: every {@code warehouse.carrier.step-interval} it advances each
 * simulated SHIPPED shipment (up to {@link TrackingService#BATCH_SIZE} per run) by one
 * scan, each in its own transaction. A backend outage ends the run; the shipment that
 * hit it is retried on the next run.
 */
@Slf4j
@Component
public class CarrierSimulator {

    private final TrackingService trackingService;
    private final Clock warehouseClock;
    private final boolean enabled;

    public CarrierSimulator(TrackingService trackingService, Clock warehouseClock,
                            @Value("${warehouse.carrier.enabled:true}") boolean enabled) {
        this.trackingService = trackingService;
        this.warehouseClock = warehouseClock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${warehouse.carrier.step-interval:PT2M}",
            initialDelayString = "${warehouse.carrier.step-interval:PT2M}")
    public void scheduledStep() {
        if (enabled) {
            // Each run is its own unit of work in the logs and in the backend calls it makes.
            CorrelationContext.generate().run(() -> advanceIdle(LocalDateTime.now(warehouseClock)));
        }
    }

    /**
     * Advances every idle simulated shipment by one scan as of {@code now}.
     *
     * @return the number of shipments advanced
     */
    public int advanceIdle(LocalDateTime now) {
        int advanced = 0;
        for (Long id : trackingService.idleSimulatedShipments(now)) {
            try {
                if (trackingService.advanceIfIdle(id, now)) {
                    advanced++;
                }
            } catch (CustomException e) {
                log.warn("Carrier step for shipment {} failed: {}", id, e.getMessage());
                if (e.getHttpStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                    break;
                }
            }
        }
        return advanced;
    }
}
