package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends outbox notifications to the backend: right after the transaction that wrote them
 * commits, and again on a schedule for those that failed. A backend outage (503) is retried
 * with exponential backoff; any other rejection (for example 404 for a deleted user) is
 * final. The backend drops duplicates by requestId, so a retry after a lost response never
 * sends a second email. Each delivery runs with the correlation ids stored at enqueue time and,
 * with tracing on, in a span whose parent is the stored {@code traceparent}. Rows given up are
 * counted in {@link OutboxMetrics}.
 *
 * <p>The after-commit trigger only hands the work to a single dispatch thread: the listener
 * runs on the committing thread before its database connection is released, so sending there
 * would need a second connection (and wait for the backend) while holding the first, which
 * starves the small pool under concurrent requests. Dispatching never runs inside a
 * transaction, and HTTP calls to the backend happen between its short transactions.
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
    private final TracingSupport tracing;
    private final OutboxMetrics metrics;
    private final boolean retryEnabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notification-dispatch");
        thread.setDaemon(true);
        return thread;
    });
    /** A dispatch is queued and has not started yet, so further triggers can rely on it. */
    private final AtomicBoolean queued = new AtomicBoolean();

    public NotificationDispatcher(NotificationOutboxRepository repository, BackendClient backendClient,
                                  PlatformTransactionManager transactionManager, Clock warehouseClock,
                                  TracingSupport tracing, OutboxMetrics metrics,
                                  @Value("${warehouse.notifications.retry-enabled:true}") boolean retryEnabled) {
        this.repository = repository;
        this.backendClient = backendClient;
        this.transaction = new TransactionTemplate(transactionManager);
        this.warehouseClock = warehouseClock;
        this.tracing = tracing;
        this.metrics = metrics;
        this.retryEnabled = retryEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterEnqueue(NotificationOutbox.Enqueued event) {
        requestDispatch();
    }

    /**
     * Queues one dispatch run unless one is already queued and not started: that run reads the
     * outbox after this call, so it also sees rows committed before it.
     */
    void requestDispatch() {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                queued.set(false);
                tracing.forJob().run(this::dispatchQuietly);
            });
        } catch (RejectedExecutionException e) {
            queued.set(false);
            log.debug("Dispatcher is shutting down; the scheduled retry sends the notification");
        }
    }

    private void dispatchQuietly() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Notification dispatch failed; the scheduled retry sends the notifications: {}", e.getMessage());
        }
    }

    /**
     * Waits until every dispatch queued before this call has finished (tests and shutdown).
     *
     * @return whether the queued dispatches finished within the timeout
     */
    public boolean awaitIdle(Duration timeout) {
        try {
            executor.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }

    @Scheduled(fixedDelayString = "${warehouse.notifications.retry-interval:PT30S}",
            initialDelayString = "${warehouse.notifications.retry-interval:PT30S}")
    public void retryDue() {
        if (retryEnabled) {
            tracing.forJob().run(this::dispatchDue);
        }
    }

    /**
     * Sends every due notification; synchronized so one instance never sends a row twice at
     * once. Must not be called inside a transaction: it opens its own short ones and calls the
     * backend between them, so a caller holding a connection would wait for a second one.
     */
    public synchronized int dispatchDue() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notifications are dispatched outside transactions");
        }
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

    /**
     * Sends with the correlation ids stored at enqueue time in the MDC, so the delivery is
     * logged and sent under the request that caused it (rows without ids get fresh ones). With
     * tracing on the delivery is a span {@code send <TEMPLATE>} parented by the stored traceparent.
     */
    private boolean send(NotificationOutboxEntity notification) {
        return tracing.deliver("send " + notification.getTemplate(), notification.getCorrelationId(),
                notification.getTraceparent(), Map.of("outbox.request_id", String.valueOf(notification.getRequestId())),
                () -> deliver(notification));
    }

    private boolean deliver(NotificationOutboxEntity notification) {
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
            AtomicBoolean givenUp = new AtomicBoolean();
            update(notification.getId(), row -> {
                int attempts = retryable ? row.getAttempts() + 1 : MAX_ATTEMPTS;
                row.setAttempts(attempts);
                row.setLastError(truncate(e.getHttpStatus().value() + " " + e.getMessage()));
                row.setNextAttemptAt(LocalDateTime.now(warehouseClock).plus(backoff(attempts)));
                givenUp.set(attempts >= MAX_ATTEMPTS);
            });
            if (givenUp.get()) {
                metrics.givenUp();
            }
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
