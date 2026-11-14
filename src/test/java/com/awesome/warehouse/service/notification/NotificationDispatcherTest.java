package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Correlation ids on outbox delivery; the delivery rules themselves are covered by the flow tests. */
class NotificationDispatcherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
    private final BackendClient backend = mock(BackendClient.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final List<Map<String, String>> sentUnder = new ArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private NotificationDispatcher dispatcher(boolean retryEnabled, NotificationOutboxEntity... rows) {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findDue(any(), anyInt(), any())).thenReturn(List.of(rows));
        when(repository.findById(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> sentUnder.add(MDC.getCopyOfContextMap())).when(backend).sendNotification(any());
        return new NotificationDispatcher(repository, backend, transactions, CLOCK, TracingSupport.disabled(),
                new OutboxMetrics(meters, repository), retryEnabled);
    }

    private static NotificationOutboxEntity row(long id, String correlationId, String traceparent) {
        return NotificationOutboxEntity.builder().id(id).requestId(UUID.randomUUID()).username("client")
                .template(NotificationTemplate.ORDER_SHIPPED).properties("{\"orderId\":\"7\"}")
                .nextAttemptAt(NOW).createdAt(NOW).correlationId(correlationId).traceparent(traceparent).build();
    }

    @Test
    void eachRowIsSentUnderTheIdsStoredAtEnqueueAndOldRowsGetFreshOnes() {
        NotificationDispatcher dispatcher = dispatcher(true,
                row(1, "req-1", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"), row(2, null, null));

        dispatcher.retryDue();
        int sentDirectly = dispatcher.dispatchDue();

        assertThat(sentDirectly).isEqualTo(2);
        assertThat(sentUnder).hasSize(4);
        assertThat(sentUnder.get(0)).containsEntry("requestId", "req-1").containsEntry("traceId", TRACE_ID);
        assertThat(sentUnder.get(0).get("spanId")).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");
        assertThat(sentUnder.get(1).get("requestId")).matches("[0-9a-f-]{36}");
        assertThat(sentUnder.get(1).get("traceId")).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aRejectedRowIsNotCountedAndIsLoggedUnderItsOwnIds() {
        NotificationDispatcher dispatcher = dispatcher(true, row(1, "req-1", null), row(2, "req-2", null));
        doAnswer(invocation -> {
            sentUnder.add(MDC.getCopyOfContextMap());
            throw new com.awesome.warehouse.controller.exception.CustomException("Backend unavailable",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }).doAnswer(invocation -> sentUnder.add(MDC.getCopyOfContextMap())).when(backend).sendNotification(any());

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(sentUnder).extracting(context -> context.get("requestId")).containsExactly("req-1", "req-2");
    }

    @Test
    void theScheduledRetryIsSkippedWhenDisabled() {
        dispatcher(false, row(1, "req-1", null)).retryDue();

        verify(backend, never()).sendNotification(any());
    }

    @Test
    void rowsGivenUpAreCountedOnceButRowsStillRetriedAreNot() {
        NotificationOutboxEntity gone = row(1, "req-1", null);
        NotificationOutboxEntity lastTry = row(2, "req-2", null);
        lastTry.setAttempts(NotificationDispatcher.MAX_ATTEMPTS - 1);
        NotificationOutboxEntity retried = row(3, "req-3", null);
        NotificationDispatcher dispatcher = dispatcher(true, gone, lastTry, retried);
        when(repository.findById(any())).thenAnswer(invocation -> Optional.of(
                List.of(gone, lastTry, retried).get(invocation.<Long>getArgument(0).intValue() - 1)));
        doAnswer(invocation -> {
            throw new com.awesome.warehouse.controller.exception.CustomException("User not found",
                    org.springframework.http.HttpStatus.NOT_FOUND);
        }).doAnswer(invocation -> {
            throw new com.awesome.warehouse.controller.exception.CustomException("Backend unavailable",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }).doAnswer(invocation -> {
            throw new com.awesome.warehouse.controller.exception.CustomException("Backend unavailable",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }).when(backend).sendNotification(any());

        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(gone.getAttempts()).isEqualTo(NotificationDispatcher.MAX_ATTEMPTS);
        assertThat(lastTry.getAttempts()).isEqualTo(NotificationDispatcher.MAX_ATTEMPTS);
        assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(meters.get(OutboxMetrics.FAILED).counter().count()).isEqualTo(2.0);
    }
}
