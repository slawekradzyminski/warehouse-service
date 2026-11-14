package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Outbox metrics of the convention in ADR 3 ("Outbox convention", metrics row), exported by the
 * Prometheus scrape: {@value #PENDING} (gauge: rows with {@code sent_at} null and fewer than
 * {@link NotificationDispatcher#MAX_ATTEMPTS} attempts, counted when scraped) and {@value #FAILED}
 * (counter: rows given up by this instance since it started).
 */
@Component
public class OutboxMetrics {

    public static final String PENDING = "warehouse.outbox.pending";
    public static final String FAILED = "warehouse.outbox.failed";

    private final Counter failed;

    public OutboxMetrics(MeterRegistry registry, NotificationOutboxRepository repository) {
        Gauge.builder(PENDING, repository, outbox -> outbox.countPending(NotificationDispatcher.MAX_ATTEMPTS))
                .description("Notification outbox rows not sent yet and still retried")
                .strongReference(true)
                .register(registry);
        this.failed = Counter.builder(FAILED)
                .description("Notification outbox rows given up (final rejection or attempts exhausted)")
                .register(registry);
    }

    /** One row reached the maximum number of attempts and will not be sent. */
    void givenUp() {
        failed.increment();
    }
}
