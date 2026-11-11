package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.correlation.CorrelationContext;
import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes customer notifications in the caller's transaction, so a notification exists
 * exactly when the shipment or return change that caused it is committed.
 * {@link NotificationDispatcher} sends them after the commit and retries failures.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationOutboxRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock warehouseClock;

    /** Published after an enqueue; the dispatcher sends right after the commit. */
    public record Enqueued() {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(CustomerMessages.CustomerMessage message) {
        LocalDateTime now = LocalDateTime.now(warehouseClock);
        // The request, event or job that caused the notification; restored on delivery.
        Optional<CorrelationContext> correlation = CorrelationContext.current();
        repository.save(NotificationOutboxEntity.builder()
                .requestId(UUID.randomUUID())
                .username(message.username())
                .template(message.template())
                .properties(JSON.writeValueAsString(message.properties()))
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .correlationId(correlation.map(CorrelationContext::requestId).orElse(null))
                .traceparent(correlation.map(CorrelationContext::traceparent).orElse(null))
                .build());
        eventPublisher.publishEvent(new Enqueued());
    }
}
