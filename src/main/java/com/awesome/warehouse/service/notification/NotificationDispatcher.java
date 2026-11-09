package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sends outbox notifications to the backend: right after the transaction that wrote them
 * commits, and again on a schedule for those that failed. A backend outage (503) is retried
 * with exponential backoff; any other rejection (for example 404 for a deleted user) is
 * final. The backend drops duplicates by requestId, so a retry after a lost response never
 * sends a second email.
 */
@Slf4j
@Component
public class NotificationDispatcher {

    static final int MAX_ATTEMPTS = 10;
    static final int BATCH_SIZE = 20;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> PROPERTIES = new TypeReference<>() {};

    private final NotificationOutboxRepository repository;
    private final BackendClient backendClient;
    private final TransactionTemplate transaction;
    private final Clock warehouseClock;
    private final boolean retryEnabled;

    public NotificationDispatcher(NotificationOutboxRepository repository, BackendClient backendClient,
                                  PlatformTransactionManager transactionManager, Clock warehouseClock,
                                  @Value("${warehouse.notifications.retry-enabled:true}") boolean retryEnabled) {
        this.repository = repository;
        this.backendClient = backendClient;
        this.transaction = new TransactionTemplate(transactionManager);
        // Runs after the enqueuing transaction committed, where REQUIRED would join that
        // finished transaction and silently lose every write.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.warehouseClock = warehouseClock;
        this.retryEnabled = retryEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterEnqueue(NotificationOutbox.Enqueued event) {
        dispatchDue();
    }

    @Scheduled(fixedDelayString = "${warehouse.notifications.retry-interval:PT30S}",
            initialDelayString = "${warehouse.notifications.retry-interval:PT30S}")
    public void retryDue() {
        if (retryEnabled) {
            dispatchDue();
        }
    }

    /** Sends every due notification; synchronized so one instance never sends a row twice at once. */
    public synchronized int dispatchDue() {
        List<NotificationOutboxEntity> due = transaction.execute(status -> repository.findDue(
                LocalDateTime.now(warehouseClock), MAX_ATTEMPTS, PageRequest.of(0, BATCH_SIZE)));
        int sent = 0;
        for (NotificationOutboxEntity notification : due == null ? List.<NotificationOutboxEntity>of() : due) {
            if (send(notification)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(NotificationOutboxEntity notification) {
        try {
            backendClient.sendNotification(new NotificationRequest(notification.getRequestId(),
                    notification.getUsername(), notification.getTemplate(),
                    JSON.readValue(notification.getProperties(), PROPERTIES)));
            update(notification.getId(), row -> row.setSentAt(LocalDateTime.now(warehouseClock)));
            return true;
        } catch (CustomException e) {
            boolean retryable = e.getHttpStatus() == HttpStatus.SERVICE_UNAVAILABLE;
            log.warn("Could not send {} notification {} to {} ({}): {}", notification.getTemplate(),
                    notification.getRequestId(), notification.getUsername(), retryable ? "will retry" : "giving up",
                    e.getMessage());
            update(notification.getId(), row -> {
                int attempts = retryable ? row.getAttempts() + 1 : MAX_ATTEMPTS;
                row.setAttempts(attempts);
                row.setLastError(truncate(e.getHttpStatus().value() + " " + e.getMessage()));
                row.setNextAttemptAt(LocalDateTime.now(warehouseClock).plus(backoff(attempts)));
            });
            return false;
        }
    }

    private void update(Long id, java.util.function.Consumer<NotificationOutboxEntity> change) {
        transaction.executeWithoutResult(status -> repository.findById(id).ifPresent(row -> {
            change.accept(row);
            repository.save(row);
        }));
    }

    static Duration backoff(int attempts) {
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
