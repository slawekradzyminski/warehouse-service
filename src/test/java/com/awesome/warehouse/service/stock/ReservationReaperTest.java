package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.stock.ReservationStatus;
import com.awesome.warehouse.entity.stock.StockReservationEntity;
import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import com.awesome.warehouse.repository.stock.StockReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationReaperTest {

    private static final LocalDateTime NOW = InMemoryStock.NOW;

    private final StockReservationRepository reservations = mock(StockReservationRepository.class);
    private final StockService stockService = mock(StockService.class);
    private final BackendClient backend = mock(BackendClient.class);
    private final StockLedger ledger = mock(StockLedger.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final StockReadiness readiness = new StockReadiness();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StockMetrics metrics = new StockMetrics(registry, mock(StockLevelOutboxRepository.class));
    private final List<StockReservationEntity> expired = new ArrayList<>();
    private final List<String> requestIdsSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        readiness.markReady();
        when(ledger.now()).thenReturn(NOW);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(reservations.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any(), any()))
                .thenAnswer(invocation -> List.copyOf(expired));
        when(backend.orderIdByReservation(any())).thenAnswer(invocation -> {
            requestIdsSeen.add(MDC.get("requestId"));
            return Optional.empty();
        });
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private ReservationReaper reaper(boolean enabled) {
        return new ReservationReaper(reservations, stockService, backend, readiness, ledger, metrics,
                TracingSupport.disabled(), transactions, Duration.ofMinutes(2), enabled);
    }

    private UUID expiredReservation(String correlationId) {
        StockReservationEntity reservation = new StockReservationEntity(UUID.randomUUID(), "alice", NOW.minusHours(1));
        reservation.setCorrelationId(correlationId);
        reservation.setTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        expired.add(reservation);
        return reservation.getReservationId();
    }

    private double reaped() {
        return registry.get(StockMetrics.RESERVATIONS_REAPED).counter().count();
    }

    @Test
    void reservationsWithoutOrderAreReleasedAndCounted() {
        UUID first = expiredReservation("checkout-1");
        UUID second = expiredReservation("checkout-2");
        when(stockService.releaseExpired(any())).thenReturn(true);

        assertThat(reaper(true).reap()).isEqualTo(2);

        verify(reservations).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(ReservationStatus.PENDING_CONFIRMATION,
                NOW.minusMinutes(2), PageRequest.of(0, ReservationReaper.BATCH_SIZE));
        verify(stockService).releaseExpired(first);
        verify(stockService).releaseExpired(second);
        assertThat(reaped()).isEqualTo(2.0);
        assertThat(requestIdsSeen).as("each reservation is handled under its checkout's request id")
                .containsExactly("checkout-1", "checkout-2");
    }

    @Test
    void aReservationReleasedMeanwhileIsNotCounted() {
        expiredReservation("checkout-1");
        when(stockService.releaseExpired(any())).thenReturn(false);

        assertThat(reaper(true).reap()).isZero();
        assertThat(reaped()).isZero();
        assertThat(reaper(true).reapOne(expired.getFirst().getReservationId()))
                .isEqualTo(ReservationReaper.Outcome.SKIPPED);
    }

    @Test
    void aReservationWithAnOrderIsConfirmedInsteadOfReleased() {
        UUID saved = expiredReservation("checkout-1");
        when(backend.orderIdByReservation(saved)).thenReturn(Optional.of(77L));
        when(stockService.confirmUnconfirmed(saved, 77L)).thenReturn(true);

        assertThat(reaper(true).reapOne(saved)).isEqualTo(ReservationReaper.Outcome.CONFIRMED);
        when(stockService.confirmUnconfirmed(saved, 77L)).thenReturn(false);
        assertThat(reaper(true).reapOne(saved)).isEqualTo(ReservationReaper.Outcome.SKIPPED);

        verify(stockService, never()).releaseExpired(any());
        assertThat(reaped()).isZero();
    }

    @Test
    void anUnreachableBackendStopsTheRun() {
        UUID first = expiredReservation("checkout-1");
        UUID second = expiredReservation("checkout-2");
        when(backend.orderIdByReservation(first))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(reaper(true).reap()).isZero();

        verify(backend, never()).orderIdByReservation(second);
        verify(stockService, never()).releaseExpired(any());
    }

    @Test
    void anotherBackendErrorSkipsOnlyThatReservation() {
        UUID first = expiredReservation("checkout-1");
        UUID second = expiredReservation("checkout-2");
        when(backend.orderIdByReservation(first))
                .thenThrow(new CustomException("Bad request", HttpStatus.BAD_REQUEST));
        when(stockService.releaseExpired(second)).thenReturn(true);

        assertThat(reaper(true).reap()).isEqualTo(1);

        verify(stockService, never()).releaseExpired(first);
        assertThat(reaper(true).reapOne(first)).isEqualTo(ReservationReaper.Outcome.SKIPPED);
        assertThat(reaper(true).reapOne(second)).isEqualTo(ReservationReaper.Outcome.RELEASED);
    }

    @Test
    void nothingIsReapedBeforeTheLedgerIsInitialised() {
        expiredReservation("checkout-1");
        readiness.markNotReady();

        assertThat(reaper(true).reap()).isZero();

        verifyNoInteractions(reservations, backend, stockService);
    }

    @Test
    void theReaperNeverRunsInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> reaper(true).reap()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reservations);
    }

    @Test
    void theScheduledRunHonoursTheSwitchAndSwallowsFailures() {
        expiredReservation("checkout-1");
        when(stockService.releaseExpired(any())).thenThrow(new IllegalStateException("database down"));

        reaper(false).scheduledReap();
        verifyNoInteractions(backend);
        reaper(true).scheduledReap();
        verify(backend).orderIdByReservation(any());
        verify(stockService, never()).confirmUnconfirmed(any(), anyLong());
    }
}
