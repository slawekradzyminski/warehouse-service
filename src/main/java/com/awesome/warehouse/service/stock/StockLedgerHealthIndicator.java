package com.awesome.warehouse.service.stock;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Health component {@code stockLedger}: UP once the ledger is initialised, {@value #IMPORTING}
 * while the startup import runs. {@code IMPORTING} is ordered after UP for {@code /actuator/health}
 * (the container healthcheck keeps answering 200 while the stock endpoints answer 503) and before
 * UP, mapped to 503, for the readiness group {@code /actuator/health/readiness} (see
 * {@code application.yml}).
 */
@Component
public class StockLedgerHealthIndicator implements HealthIndicator {

    public static final String IMPORTING = "IMPORTING";

    private final StockReadiness readiness;

    public StockLedgerHealthIndicator(StockReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        return readiness.isReady()
                ? Health.up().build()
                : Health.status(new Status(IMPORTING, StockReadiness.NOT_INITIALISED)).build();
    }
}
