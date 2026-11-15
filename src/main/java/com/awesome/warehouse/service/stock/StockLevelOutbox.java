package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.correlation.CorrelationContext;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.entity.stock.StockLevelOutboxEntity;
import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Writes the new level of an item in the transaction of the ledger change, so the backend
 * projection is pushed exactly for committed changes. {@link StockLevelDispatcher} pushes the
 * rows after the commit and retries them while the backend is unreachable.
 */
@Component
@RequiredArgsConstructor
public class StockLevelOutbox {

    private final StockLevelOutboxRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock warehouseClock;
    private final TracingSupport tracing;

    /** Published after an enqueue; the dispatcher pushes right after the commit. */
    public record Enqueued() {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(StockItemEntity item) {
        Optional<CorrelationContext> correlation = CorrelationContext.current();
        repository.save(StockLevelOutboxEntity.builder()
                .productId(item.getProductId())
                .quantity(item.getQuantity())
                .ledgerVersion(item.getLedgerVersion())
                .attempts(0)
                .createdAt(LocalDateTime.now(warehouseClock.withZone(ZoneOffset.UTC)))
                .correlationId(correlation.map(CorrelationContext::requestId).orElse(null))
                .traceparent(tracing.currentTraceparent().orElse(null))
                .build());
        eventPublisher.publishEvent(new Enqueued());
    }
}
