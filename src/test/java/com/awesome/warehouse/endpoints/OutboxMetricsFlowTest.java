package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import com.awesome.warehouse.service.notification.NotificationDispatcher;
import com.awesome.warehouse.service.notification.OutboxMetrics;
import com.awesome.warehouse.support.AbstractFlowTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code warehouse.outbox.pending} and {@code warehouse.outbox.failed} (ADR 3 metrics row) on the Prometheus scrape. */
class OutboxMetricsFlowTest extends AbstractFlowTest {

    /** NotificationDispatcher.MAX_ATTEMPTS (ADR 3: at most 10 attempts). */
    private static final int MAX_ATTEMPTS = 10;

    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private MeterRegistry meters;

    @Test
    void pendingCountsUnsentRowsBelowTheAttemptLimitAndTheScrapeExportsBothMeters() {
        LocalDateTime now = LocalDateTime.now();
        outbox.save(row(0, null, now.plusHours(1)));
        outbox.save(row(3, null, now.plusHours(1)));
        outbox.save(row(1, now, now));
        outbox.save(row(MAX_ATTEMPTS, null, now));

        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isEqualTo(2.0);
        String scrape = getJson("/actuator/prometheus", null).body();
        assertThat(scrape).contains("warehouse_outbox_pending 2.0").containsPattern("warehouse_outbox_failed_total \\d");
    }

    @Test
    void aRowThatUsesItsLastAttemptIsGivenUpCountedAndNoLongerPending() {
        double failedBefore = meters.get(OutboxMetrics.FAILED).counter().count();
        outbox.save(row(MAX_ATTEMPTS - 1, null, LocalDateTime.now().minusMinutes(1)));
        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isEqualTo(1.0);
        backend.setNotificationsDown(true);

        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isZero();
        assertThat(meters.get(OutboxMetrics.FAILED).counter().count()).isEqualTo(failedBefore + 1);
    }

    private static NotificationOutboxEntity row(int attempts, LocalDateTime sentAt, LocalDateTime nextAttemptAt) {
        return NotificationOutboxEntity.builder().requestId(UUID.randomUUID()).username("client")
                .template(NotificationTemplate.ORDER_SHIPPED).properties("{\"orderId\":\"7\"}")
                .attempts(attempts).sentAt(sentAt).nextAttemptAt(nextAttemptAt).createdAt(LocalDateTime.now()).build();
    }
}
