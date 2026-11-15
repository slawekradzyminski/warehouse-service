package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.StockLevel;
import com.awesome.warehouse.client.StockLevelsRequest;
import com.awesome.warehouse.client.StockLevelsResponse;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.stock.StockLevelOutboxEntity;
import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes the stock level outbox to the backend projection
 * ({@code POST /internal/v1/inventory/levels}, contract 3.3): right after the transaction that
 * wrote the levels commits, and again every {@code WAREHOUSE_STOCK_PUSH_RETRY_INTERVAL} for rows
 * the backend did not accept. A batch sends the newest level of each product; the backend keeps
 * only levels with a newer {@code ledgerVersion}, so a resend or an out-of-order push is harmless.
 * Rows are retried until the backend accepts them (the {@code stock.outbox.pending} gauge shows
 * the backlog) and deleted once it has.
 *
 * <p>As in the notification outbox, the after-commit trigger only hands the work to a single
 * dispatch thread, and pushing never runs inside a transaction. With tracing on each push is a
 * {@code PRODUCER} span {@code push stock levels} whose parent is the change that wrote the
 * batch's first row.
 */
@Slf4j
@Component
public class StockLevelDispatcher {

    static final int BATCH_SIZE = 100;
    static final int MAX_BATCHES_PER_RUN = 20;

    private final StockLevelOutboxRepository repository;
    private final BackendClient backendClient;
    private final TransactionTemplate transaction;
    private final TracingSupport tracing;
    private final boolean retryEnabled;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stock-level-push");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean queued = new AtomicBoolean();

    public StockLevelDispatcher(StockLevelOutboxRepository repository, BackendClient backendClient,
                                PlatformTransactionManager transactionManager, TracingSupport tracing,
                                @Value("${warehouse.stock.push-retry-enabled:true}") boolean retryEnabled) {
        this.repository = repository;
        this.backendClient = backendClient;
        this.transaction = new TransactionTemplate(transactionManager);
        this.tracing = tracing;
        this.retryEnabled = retryEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterEnqueue(StockLevelOutbox.Enqueued event) {
        requestDispatch();
    }

    /** Queues one push run unless one is queued and not started yet (it will see these rows too). */
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
            log.debug("Stock level dispatcher is shutting down; the scheduled retry pushes the levels");
        }
    }

    private void dispatchQuietly() {
        try {
            dispatchPending();
        } catch (RuntimeException e) {
            log.warn("Stock level push failed; the scheduled retry pushes the levels: {}", e.getMessage());
        }
    }

    /**
     * Waits until every push queued before this call has finished (tests and shutdown).
     *
     * @return whether the queued pushes finished within the timeout
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

    @Scheduled(fixedDelayString = "${warehouse.stock.push-retry-interval:PT5S}",
            initialDelayString = "${warehouse.stock.push-retry-interval:PT5S}")
    public void retryDue() {
        if (retryEnabled) {
            tracing.forJob().run(this::dispatchQuietly);
        }
    }

    /**
     * Pushes pending levels batch by batch until the outbox is empty or the backend refuses one.
     * Must not be called inside a transaction.
     *
     * @return the number of product levels the backend accepted in this run
     */
    public synchronized int dispatchPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Stock levels are pushed outside transactions");
        }
        int pushed = 0;
        for (int batchNumber = 0; batchNumber < MAX_BATCHES_PER_RUN; batchNumber++) {
            List<StockLevelOutboxEntity> batch = transaction.execute(status ->
                    repository.findAllByOrderByIdAsc(PageRequest.of(0, BATCH_SIZE)));
            if (batch == null || batch.isEmpty()) {
                break;
            }
            List<StockLevel> levels = newestPerProduct(batch);
            StockLevelOutboxEntity first = batch.getFirst();
            boolean accepted = tracing.deliver("push stock levels", first.getCorrelationId(), first.getTraceparent(),
                    Map.of("stock.levels", Integer.toString(levels.size())), () -> push(batch, levels));
            if (!accepted) {
                break;
            }
            pushed += levels.size();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return pushed;
    }

    private boolean push(List<StockLevelOutboxEntity> batch, List<StockLevel> levels) {
        List<Long> ids = batch.stream().map(StockLevelOutboxEntity::getId).toList();
        try {
            StockLevelsResponse response = backendClient.pushStockLevels(new StockLevelsRequest(levels));
            transaction.executeWithoutResult(status -> repository.deleteAllByIdInBatch(ids));
            log.debug("Pushed {} stock levels ({} applied, {} ignored)", levels.size(), response.applied(),
                    response.ignored());
            return true;
        } catch (CustomException e) {
            String error = truncate(e.getHttpStatus().value() + " " + e.getMessage());
            transaction.executeWithoutResult(status -> repository.recordFailure(ids, error));
            log.warn("Could not push {} stock levels to the backend ({}); retrying", levels.size(), error);
            return false;
        }
    }

    /** The newest level (highest ledger version) of each product in the batch, in product id order. */
    static List<StockLevel> newestPerProduct(List<StockLevelOutboxEntity> batch) {
        Map<Long, StockLevelOutboxEntity> newest = new TreeMap<>();
        for (StockLevelOutboxEntity row : batch) {
            newest.merge(row.getProductId(), row, (a, b) -> b.getLedgerVersion() > a.getLedgerVersion() ? b : a);
        }
        return newest.values().stream()
                .sorted(Comparator.comparing(StockLevelOutboxEntity::getProductId))
                .map(row -> new StockLevel(row.getProductId(), row.getQuantity(), row.getLedgerVersion()))
                .toList();
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
