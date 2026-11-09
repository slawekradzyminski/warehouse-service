package com.awesome.warehouse.service.returns;

import com.awesome.warehouse.entity.returns.OrderLockEntity;
import com.awesome.warehouse.repository.returns.OrderLockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serialises return requests for the same order across every warehouse-service instance
 * with a database row lock. Orders live in the backend, so the lock is a row in
 * {@code order_locks}: it is created once (in its own transaction, so a concurrent creator
 * merely loses the insert) and then locked with {@code SELECT ... FOR UPDATE} until the
 * caller's transaction ends.
 */
@Slf4j
@Component
public class OrderLocks {

    private final OrderLockRepository repository;
    private final TransactionTemplate newTransaction;

    public OrderLocks(OrderLockRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Blocks until the caller's transaction holds the lock for this order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long orderId) {
        if (repository.findForUpdate(orderId).isPresent()) {
            return;
        }
        try {
            newTransaction.executeWithoutResult(status -> repository.saveAndFlush(new OrderLockEntity(orderId)));
        } catch (DataIntegrityViolationException e) {
            log.debug("Order {} lock row was created concurrently; waiting for it", orderId);
        }
        repository.findForUpdate(orderId).orElseThrow(() -> new IllegalStateException("Order lock row is missing"));
    }
}
