package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.entity.stock.ReservationStatus;
import com.awesome.warehouse.entity.stock.StockReservationEntity;
import com.awesome.warehouse.repository.stock.StockReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Releases checkout reservations the backend never confirmed (contract 3.4): every
 * {@code WAREHOUSE_RESERVATION_REAPER_INTERVAL}, reservations still PENDING_CONFIRMATION after
 * {@code WAREHOUSE_RESERVATION_CONFIRM_TIMEOUT}. Before releasing one it asks the backend
 * ({@code GET /internal/v1/orders/by-reservation/{id}}) whether an order was saved for it; if so
 * the reservation is confirmed for that order instead (the backend's confirm call was lost). When
 * the backend cannot be asked the run stops and the next one retries.
 *
 * <p>Each reservation is handled in a {@code PRODUCER} span {@code reap reservation} whose parent is
 * the checkout request that reserved it; the scheduled run itself is not recorded. No database
 * connection is held while the backend is asked.
 */
@Slf4j
@Component
public class ReservationReaper {

    static final int BATCH_SIZE = 50;

    private final StockReservationRepository reservations;
    private final StockService stockService;
    private final BackendClient backendClient;
    private final StockReadiness readiness;
    private final StockLedger ledger;
    private final StockMetrics metrics;
    private final TracingSupport tracing;
    private final TransactionTemplate transaction;
    private final Duration confirmTimeout;
    private final boolean enabled;

    public ReservationReaper(StockReservationRepository reservations, StockService stockService,
                             BackendClient backendClient, StockReadiness readiness, StockLedger ledger,
                             StockMetrics metrics, TracingSupport tracing, PlatformTransactionManager transactionManager,
                             @Value("${warehouse.stock.reservation-confirm-timeout:PT2M}") Duration confirmTimeout,
                             @Value("${warehouse.stock.reaper-enabled:true}") boolean enabled) {
        this.reservations = reservations;
        this.stockService = stockService;
        this.backendClient = backendClient;
        this.readiness = readiness;
        this.ledger = ledger;
        this.metrics = metrics;
        this.tracing = tracing;
        this.transaction = new TransactionTemplate(transactionManager);
        this.confirmTimeout = confirmTimeout;
        this.enabled = enabled;
    }

    /** What happened to one expired reservation. */
    enum Outcome { RELEASED, CONFIRMED, SKIPPED, STOPPED }

    /** The ids of an expired reservation, read in a short transaction. */
    private record Expired(UUID reservationId, String correlationId, String traceparent) {

        static Expired from(StockReservationEntity reservation) {
            return new Expired(reservation.getReservationId(), reservation.getCorrelationId(),
                    reservation.getTraceparent());
        }
    }

    @Scheduled(fixedDelayString = "${warehouse.stock.reservation-reaper-interval:PT30S}",
            initialDelayString = "${warehouse.stock.reservation-reaper-interval:PT30S}")
    public void scheduledReap() {
        if (enabled) {
            tracing.forJob().run(this::reapQuietly);
        }
    }

    private void reapQuietly() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("Reservation reaper run failed; the next run retries: {}", e.getMessage());
        }
    }

    /**
     * Handles one batch of expired reservations, oldest first.
     *
     * @return the number of reservations released in this run
     */
    public synchronized int reap() {
        if (!readiness.isReady()) {
            return 0;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The reaper calls the backend outside transactions");
        }
        List<Expired> expired = transaction.execute(status -> reservations
                .findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(ReservationStatus.PENDING_CONFIRMATION,
                        ledger.now().minus(confirmTimeout), PageRequest.of(0, BATCH_SIZE))
                .stream().map(Expired::from).toList());
        int released = 0;
        for (Expired reservation : expired == null ? List.<Expired>of() : expired) {
            Outcome outcome = tracing.deliver("reap reservation", reservation.correlationId(),
                    reservation.traceparent(), Map.of("reservation.id", reservation.reservationId().toString()),
                    () -> reapOne(reservation.reservationId()));
            if (outcome == Outcome.STOPPED) {
                break;
            }
            if (outcome == Outcome.RELEASED) {
                released++;
            }
        }
        return released;
    }

    Outcome reapOne(UUID reservationId) {
        Optional<Long> orderId;
        try {
            orderId = backendClient.orderIdByReservation(reservationId);
        } catch (CustomException e) {
            if (e.getHttpStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                log.warn("Reservation {} not reaped: the backend could not be asked; retrying next run", reservationId);
                return Outcome.STOPPED;
            }
            log.warn("Reservation {} not reaped: the backend answered {} {}", reservationId,
                    e.getHttpStatus().value(), e.getMessage());
            return Outcome.SKIPPED;
        }
        if (orderId.isPresent()) {
            if (stockService.confirmUnconfirmed(reservationId, orderId.get())) {
                log.info("Reservation {} confirmed for order {} by the reaper", reservationId, orderId.get());
                return Outcome.CONFIRMED;
            }
            return Outcome.SKIPPED;
        }
        if (stockService.releaseExpired(reservationId)) {
            metrics.reaped();
            return Outcome.RELEASED;
        }
        return Outcome.SKIPPED;
    }
}
