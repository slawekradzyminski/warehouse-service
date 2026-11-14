package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Stock metrics (contract 3.4), exported by the Prometheus scrape: {@value #OUTBOX_PENDING}
 * (gauge: levels not pushed to the backend yet, counted when scraped; it grows while the backend
 * is unreachable) and {@value #RESERVATIONS_REAPED} (counter: unconfirmed reservations the reaper
 * released since this instance started).
 */
@Component
public class StockMetrics {

    public static final String OUTBOX_PENDING = "stock.outbox.pending";
    public static final String RESERVATIONS_REAPED = "stock.reservations.reaped";

    private final Counter reapedCounter;

    public StockMetrics(MeterRegistry registry, StockLevelOutboxRepository outbox) {
        Gauge.builder(OUTBOX_PENDING, outbox, StockLevelOutboxRepository::count)
                .description("Stock levels waiting to be pushed to the backend projection")
                .strongReference(true)
                .register(registry);
        this.reapedCounter = Counter.builder(RESERVATIONS_REAPED)
                .description("Stock reservations released because the backend never confirmed them")
                .register(registry);
    }

    void reaped() {
        reapedCounter.increment();
    }
}
