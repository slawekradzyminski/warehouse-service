package com.awesome.warehouse.service.returns;

import com.awesome.warehouse.entity.returns.OrderLockEntity;
import com.awesome.warehouse.repository.returns.OrderLockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Serialises writes for the same order (return requests and every fulfilment write of the
 * order's parcels) across every warehouse-service instance with a database row lock. Orders live in the backend, so the lock is a row in
 * {@code order_locks}: it is created once in its own short transaction (a concurrent creator
 * merely loses the insert) and then locked with {@code SELECT ... FOR UPDATE} in the
 * transaction that runs the caller's work. The row is created before that transaction
 * starts, because creating it inside would need a second connection while the first one is
 * held, which starves the pool under concurrent requests.
 */
@Slf4j
@Component
public class OrderLocks {

    private final OrderLockRepository repository;
    private final TransactionTemplate transaction;

    public OrderLocks(OrderLockRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs {@code work} in a new transaction that holds this order's lock until it ends. Call it
     * outside any transaction; the work's repository calls join the locking transaction.
     */
    public <T> T callLocked(long orderId, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Order locks are taken in their own transaction");
        }
        ensureExists(orderId);
        return transaction.execute(status -> {
            repository.findForUpdate(orderId).orElseThrow(() -> new IllegalStateException("Order lock row is missing"));
            return work.get();
        });
    }

    /**
     * Locks this order's row in the caller's transaction and returns it (the fulfilment
     * writes keep the last mirrored fulfilment state there). Call {@link #ensureExists} before
     * the transaction starts; a row that is still missing is inserted here, in the same
     * transaction and so on the same connection, and the insert itself holds the key lock.
     */
    public OrderLockEntity lockInTransaction(long orderId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Order locks are taken inside a transaction");
        }
        return repository.findForUpdate(orderId)
                .orElseGet(() -> repository.saveAndFlush(new OrderLockEntity(orderId)));
    }

    /**
     * Creates the order's lock row in its own short transaction unless it exists. Call it
     * outside any transaction (inside one it does nothing: {@link #lockInTransaction} creates
     * the row).
     */
    public void ensureExists(long orderId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        try {
            transaction.executeWithoutResult(status -> {
                if (!repository.existsById(orderId)) {
                    repository.saveAndFlush(new OrderLockEntity(orderId));
                }
            });
        } catch (DataIntegrityViolationException e) {
            log.debug("Order {} lock row was created concurrently", orderId);
        }
    }
}
