package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.entity.stock.StockReservationEntity;
import com.awesome.warehouse.repository.stock.StockItemRepository;
import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import com.awesome.warehouse.repository.stock.StockMovementRepository;
import com.awesome.warehouse.repository.stock.StockReservationRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Mocked stock repositories backed by maps, so the ledger and the stock service can be unit tested
 * (and mutation tested) without Spring: what they save is what they later find.
 */
final class InMemoryStock {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 10, 0, 0, 123_456_000);
    static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    final Map<Long, StockItemEntity> itemRows = new LinkedHashMap<>();
    final List<StockMovementEntity> movementRows = new ArrayList<>();
    final Map<UUID, StockReservationEntity> reservationRows = new LinkedHashMap<>();
    final List<Long> lockOrder = new ArrayList<>();
    final StockItemRepository items = mock(StockItemRepository.class);
    final StockMovementRepository movements = mock(StockMovementRepository.class);
    final StockReservationRepository reservations = mock(StockReservationRepository.class);
    final StockLevelOutboxRepository outboxRows = mock(StockLevelOutboxRepository.class);
    final StockLevelOutbox levelOutbox = mock(StockLevelOutbox.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final StockReadiness readiness = new StockReadiness();
    final StockLedger ledger = new StockLedger(items, movements, levelOutbox, readiness, CLOCK);
    private final AtomicLong movementIds = new AtomicLong(100);

    InMemoryStock() {
        readiness.markReady();
        lenient().when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(items.findForUpdate(any())).thenAnswer(invocation -> {
            lockOrder.add(invocation.getArgument(0));
            return Optional.ofNullable(itemRows.get(invocation.<Long>getArgument(0)));
        });
        lenient().when(items.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(itemRows.get(invocation.<Long>getArgument(0))));
        lenient().when(items.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(items.saveAndFlush(any())).thenAnswer(invocation -> {
            StockItemEntity item = invocation.getArgument(0);
            itemRows.put(item.getProductId(), item);
            return item;
        });
        lenient().when(movements.save(any())).thenAnswer(invocation -> {
            StockMovementEntity movement = invocation.getArgument(0);
            if (movement.getId() == null) {
                movement.setId(movementIds.incrementAndGet());
                movementRows.add(movement);
            }
            return movement;
        });
        lenient().when(movements.findByProductIdAndRequestId(any(), any())).thenAnswer(invocation ->
                movementRows.stream().filter(m -> m.getProductId().equals(invocation.getArgument(0))
                        && Objects.equals(m.getRequestId(), invocation.getArgument(1))).findFirst());
        lenient().when(movements.findByReservationIdOrderByIdAsc(any())).thenAnswer(invocation ->
                movementRows.stream().filter(m -> invocation.getArgument(0).equals(m.getReservationId())).toList());
        lenient().when(movements.findByProductIdOrderByCreatedAtDescIdDesc(any(), any())).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            List<StockMovementEntity> all = movementRows.stream()
                    .filter(m -> m.getProductId().equals(invocation.getArgument(0))).toList().reversed();
            return new PageImpl<>(all.stream().skip(pageable.getOffset()).limit(pageable.getPageSize()).toList(),
                    pageable, all.size());
        });
        lenient().when(reservations.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(reservationRows.get(invocation.<UUID>getArgument(0))));
        lenient().when(reservations.findForUpdate(any()))
                .thenAnswer(invocation -> Optional.ofNullable(reservationRows.get(invocation.<UUID>getArgument(0))));
        lenient().when(reservations.saveAndFlush(any())).thenAnswer(invocation -> {
            StockReservationEntity reservation = invocation.getArgument(0);
            reservationRows.put(reservation.getReservationId(), reservation);
            return reservation;
        });
        lenient().when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    StockItemEntity item(long productId, int quantity, long ledgerVersion) {
        StockItemEntity item = new StockItemEntity(productId, quantity, ledgerVersion, NOW.minusDays(1));
        itemRows.put(productId, item);
        return item;
    }

    List<StockMovementEntity> movementsOf(long productId) {
        return movementRows.stream().filter(m -> m.getProductId() == productId).toList();
    }
}
