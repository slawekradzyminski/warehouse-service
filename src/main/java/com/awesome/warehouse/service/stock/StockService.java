package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.controller.exception.StockException;
import com.awesome.warehouse.correlation.CorrelationContext;
import com.awesome.warehouse.correlation.TracingSupport;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.dto.stock.InitialStockRequestDto;
import com.awesome.warehouse.dto.stock.InventoryMovementDto;
import com.awesome.warehouse.dto.stock.ReleasedReservationDto;
import com.awesome.warehouse.dto.stock.ReservationDto;
import com.awesome.warehouse.dto.stock.ReserveStockRequestDto;
import com.awesome.warehouse.dto.stock.ReservedLineDto;
import com.awesome.warehouse.dto.stock.StockAdjustmentDto;
import com.awesome.warehouse.dto.stock.StockAdjustmentRequestDto;
import com.awesome.warehouse.dto.stock.StockItemDto;
import com.awesome.warehouse.dto.stock.StockLineDto;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.ReservationStatus;
import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.entity.stock.StockReservationEntity;
import com.awesome.warehouse.entity.stock.StockReservationLineEntity;
import com.awesome.warehouse.repository.stock.StockItemRepository;
import com.awesome.warehouse.repository.stock.StockLevelOutboxRepository;
import com.awesome.warehouse.repository.stock.StockMovementRepository;
import com.awesome.warehouse.repository.stock.StockReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The stock commands and reads the backend calls ({@code /internal/v1/stock}, contract section 2).
 * Every operation answers 503 until the ledger is initialised ({@link StockReadiness}).
 *
 * <p>The adjustment rules and messages are exactly those of the backend's former
 * {@code InventoryService.adjust}; reserve and release lock the items in product id order.
 */
@Slf4j
@Service
public class StockService {

    static final String ORDER_DEDUCTION = "Order deduction";
    static final String RESERVATION_RELEASED = "Reservation released";
    static final String RESERVATION_EXPIRED = "Reservation expired unconfirmed";
    static final String SYSTEM_ACTOR = "system";
    static final String RESERVATION_NOT_FOUND = "Reservation not found";
    static final String DIFFERENT_LINES = "reservationId already used with different lines";
    static final String ALREADY_RELEASED = "Reservation already released";
    static final String RESERVED_CONCURRENTLY = "Reservation was created concurrently; release it again";
    /** The username of a release tombstone: no reserve ever arrived to name the buyer. */
    static final String TOMBSTONE_USER = "system";
    static final int MAX_PAGE_SIZE = 100;

    private final StockItemRepository items;
    private final StockMovementRepository movementRepository;
    private final StockReservationRepository reservations;
    private final StockLevelOutboxRepository levelOutbox;
    private final StockLedger ledger;
    private final StockReadiness readiness;
    private final TracingSupport tracing;
    private final TransactionTemplate transaction;

    public StockService(StockItemRepository items, StockMovementRepository movements,
                        StockReservationRepository reservations, StockLevelOutboxRepository levelOutbox,
                        StockLedger ledger, StockReadiness readiness, TracingSupport tracing,
                        PlatformTransactionManager transactionManager) {
        this.items = items;
        this.movementRepository = movements;
        this.reservations = reservations;
        this.levelOutbox = levelOutbox;
        this.ledger = ledger;
        this.readiness = readiness;
        this.tracing = tracing;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The result of an initial-stock call: the item and whether this call created it. */
    public record CreatedItem(StockItemDto item, boolean created) {
    }

    /**
     * Deducts every line in one transaction (contract 2.1): the items are locked in product id
     * order, every line is checked, then every line is deducted. A repeated call with the same
     * reservation id and lines returns the stored reservation.
     */
    @Transactional
    public ReservationDto reserve(ReserveStockRequestDto request) {
        readiness.requireReady();
        Map<Long, Integer> requested = requestedLines(request.lines());
        Map<Long, StockItemEntity> locked = ledger.lockInOrder(requested.keySet());
        Optional<StockReservationEntity> existing = reservations.findById(request.reservationId());
        if (existing.isPresent()) {
            return replay(existing.get(), requested);
        }
        for (Long productId : requested.keySet()) {
            if (!locked.containsKey(productId)) {
                throw new StockException("Product " + productId + " not found", HttpStatus.NOT_FOUND, productId);
            }
        }
        requested.forEach((productId, quantity) -> {
            if (locked.get(productId).getQuantity() < quantity) {
                throw new StockException("Insufficient stock for product " + productId, HttpStatus.CONFLICT, productId);
            }
        });
        StockReservationEntity reservation = new StockReservationEntity(request.reservationId(), request.username(),
                ledger.now());
        reservation.setCorrelationId(CorrelationContext.current().map(CorrelationContext::requestId).orElse(null));
        reservation.setTraceparent(tracing.currentTraceparent().orElse(null));
        requested.forEach((productId, quantity) -> {
            StockMovementEntity movement = ledger.record(locked.get(productId), new StockLedger.Change(
                    InventoryMovementType.ORDER_DEDUCTED, -quantity, null, request.reservationId(),
                    request.username(), ORDER_DEDUCTION, null));
            reservation.addLine(StockReservationLineEntity.builder()
                    .productId(productId)
                    .quantity(quantity)
                    .quantityAfter(movement.getQuantityAfter())
                    .ledgerVersion(movement.getLedgerVersion())
                    .build());
        });
        try {
            reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // A concurrent reserve with the same id and other products won the insert.
            throw new StockException(DIFFERENT_LINES, HttpStatus.CONFLICT, null, e);
        }
        return toDto(reservation);
    }

    /** Confirms a reservation for its order (contract 2.2); idempotent for the same order. */
    @Transactional
    public ReservationDto confirm(UUID reservationId, long orderId) {
        readiness.requireReady();
        StockReservationEntity reservation = lockReservation(reservationId);
        switch (reservation.getStatus()) {
            case RELEASED -> throw new CustomException(ALREADY_RELEASED, HttpStatus.CONFLICT);
            case CONFIRMED -> {
                if (!Objects.equals(reservation.getOrderId(), orderId)) {
                    throw new CustomException("Reservation already confirmed for another order", HttpStatus.CONFLICT);
                }
            }
            case PENDING_CONFIRMATION -> markConfirmed(reservation, orderId);
        }
        return toDto(reservation);
    }

    /**
     * Puts back the stock of a pending reservation (contract 2.3); repeated calls answer the
     * same body, a confirmed reservation is restored only through {@code order.cancelled}.
     *
     * <p>An unknown id is stored as a RELEASED tombstone without lines and answers the same 200:
     * the backend releases a reservation whose reserve call timed out, and that reserve may still
     * be on its way. When it arrives, it finds the tombstone and deducts nothing.
     */
    @Transactional
    public ReleasedReservationDto release(UUID reservationId) {
        readiness.requireReady();
        Optional<StockReservationEntity> stored = reservations.findForUpdate(reservationId);
        if (stored.isEmpty()) {
            storeTombstone(reservationId);
            return new ReleasedReservationDto(reservationId, ReservationStatus.RELEASED);
        }
        StockReservationEntity reservation = stored.get();
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            throw new CustomException("Reservation already confirmed", HttpStatus.CONFLICT);
        }
        if (reservation.getStatus() == ReservationStatus.PENDING_CONFIRMATION) {
            putBack(reservation, RESERVATION_RELEASED);
        }
        return new ReleasedReservationDto(reservationId, ReservationStatus.RELEASED);
    }

    private void storeTombstone(UUID reservationId) {
        StockReservationEntity tombstone = new StockReservationEntity(reservationId, TOMBSTONE_USER, ledger.now());
        tombstone.setStatus(ReservationStatus.RELEASED);
        tombstone.setCorrelationId(CorrelationContext.current().map(CorrelationContext::requestId).orElse(null));
        try {
            reservations.saveAndFlush(tombstone);
        } catch (DataIntegrityViolationException e) {
            // The reserve committed this id meanwhile; the backend's next release (or the reaper) puts it back.
            throw new CustomException(RESERVED_CONCURRENTLY, HttpStatus.CONFLICT, e);
        }
        log.info("Reservation {} released before it was reserved; a late reserve with this id deducts nothing",
                reservationId);
    }

    /**
     * Reaper: releases a reservation that is still pending with the reason
     * "Reservation expired unconfirmed".
     *
     * @return whether the reservation was released by this call
     */
    @Transactional
    public boolean releaseExpired(UUID reservationId) {
        Optional<StockReservationEntity> reservation = pendingForUpdate(reservationId);
        reservation.ifPresent(pending -> putBack(pending, RESERVATION_EXPIRED));
        return reservation.isPresent();
    }

    /**
     * Reaper: confirms a reservation that is still pending for the order the backend saved for it.
     *
     * @return whether the reservation was confirmed by this call
     */
    @Transactional
    public boolean confirmUnconfirmed(UUID reservationId, long orderId) {
        Optional<StockReservationEntity> reservation = pendingForUpdate(reservationId);
        reservation.ifPresent(pending -> markConfirmed(pending, orderId));
        return reservation.isPresent();
    }

    /** Admin adjustment with the rules and messages of the backend's former {@code InventoryService.adjust}. */
    @Transactional
    public StockAdjustmentDto adjust(StockAdjustmentRequestDto request) {
        readiness.requireReady();
        if (request.delta() == 0) {
            throw new CustomException("Inventory delta must not be zero", HttpStatus.BAD_REQUEST);
        }
        StockItemEntity item = items.findForUpdate(request.productId()).orElseThrow(StockService::productNotFound);
        Optional<StockMovementEntity> prior = movementRepository.findByProductIdAndRequestId(
                request.productId(), request.requestId());
        if (prior.isPresent()) {
            StockMovementEntity movement = prior.get();
            if (!Objects.equals(movement.getDelta(), request.delta())
                    || !Objects.equals(movement.getReason(), request.reason())) {
                throw new CustomException("requestId already used with different payload", HttpStatus.CONFLICT);
            }
            return toAdjustmentDto(movement);
        }
        long resulting = StockLedger.resulting(item, request.delta());
        if (resulting < 0) {
            throw new CustomException("Insufficient stock", HttpStatus.CONFLICT);
        }
        if (resulting > Integer.MAX_VALUE) {
            throw new CustomException(StockLedger.MAXIMUM_EXCEEDED, HttpStatus.CONFLICT);
        }
        return toAdjustmentDto(ledger.record(item, new StockLedger.Change(InventoryMovementType.ADMIN_ADJUSTMENT,
                request.delta(), null, null, request.actor(), request.reason(), request.requestId())));
    }

    /**
     * Creates the item of a new product (contract 2.5); an existing item is returned unchanged,
     * also when a concurrent call created it first.
     */
    public CreatedItem createItem(Long productId, InitialStockRequestDto request) {
        readiness.requireReady();
        try {
            StockItemDto created = transaction.execute(status -> {
                Optional<StockItemEntity> existing = items.findById(productId);
                return existing.isPresent() ? null
                        : toDto(ledger.create(productId, request.initialQuantity(), request.actor()));
            });
            if (created != null) {
                log.info("Stock item of product {} created with {} units", productId, request.initialQuantity());
                return new CreatedItem(created, true);
            }
        } catch (DataIntegrityViolationException e) {
            log.debug("Stock item of product {} created concurrently", productId);
        }
        return new CreatedItem(item(productId), false);
    }

    /** Deletes a product's item, movements, reservation lines and pending levels (contract 2.6); idempotent. */
    @Transactional
    public void deleteItem(Long productId) {
        readiness.requireReady();
        Optional<StockItemEntity> item = items.findForUpdate(productId);
        reservations.deleteLinesOfProduct(productId);
        reservations.deleteWithoutLines();
        levelOutbox.deleteByProduct(productId);
        movementRepository.deleteByProduct(productId);
        item.ifPresent(items::delete);
        log.info("Stock item of product {} deleted", productId);
    }

    @Transactional(readOnly = true)
    public StockItemDto item(Long productId) {
        readiness.requireReady();
        return items.findById(productId).map(StockService::toDto).orElseThrow(StockService::productNotFound);
    }

    /**
     * A product's movements newest first ({@code createdAt} desc, then id desc), paged like the
     * backend's admin endpoint: a negative page is clamped to 0, the size to 1..100. A product
     * without an item has no movements.
     */
    @Transactional(readOnly = true)
    public PageDto<InventoryMovementDto> movements(Long productId, int page, int size) {
        readiness.requireReady();
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return PageDto.from(movementRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId, pageable)
                .map(StockService::toMovementDto));
    }

    private static Map<Long, Integer> requestedLines(List<StockLineDto> lines) {
        Map<Long, Integer> requested = new TreeMap<>();
        for (StockLineDto line : lines) {
            if (requested.put(line.productId(), line.quantity()) != null) {
                throw new CustomException("Product " + line.productId() + " is listed more than once",
                        HttpStatus.BAD_REQUEST);
            }
        }
        return requested;
    }

    private static ReservationDto replay(StockReservationEntity reservation, Map<Long, Integer> requested) {
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            // Never hand out a released reservation as if it held stock (a tombstone has no lines at all).
            throw new StockException(ALREADY_RELEASED, HttpStatus.CONFLICT, null);
        }
        Map<Long, Integer> stored = reservation.getLines().stream().collect(Collectors.toMap(
                StockReservationLineEntity::getProductId, StockReservationLineEntity::getQuantity, Integer::sum,
                TreeMap::new));
        if (!stored.equals(requested)) {
            throw new StockException(DIFFERENT_LINES, HttpStatus.CONFLICT, null);
        }
        return toDto(reservation);
    }

    private StockReservationEntity lockReservation(UUID reservationId) {
        return reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new CustomException(RESERVATION_NOT_FOUND, HttpStatus.NOT_FOUND));
    }

    private Optional<StockReservationEntity> pendingForUpdate(UUID reservationId) {
        readiness.requireReady();
        return reservations.findForUpdate(reservationId)
                .filter(reservation -> reservation.getStatus() == ReservationStatus.PENDING_CONFIRMATION);
    }

    private void markConfirmed(StockReservationEntity reservation, long orderId) {
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setOrderId(orderId);
        reservation.setUpdatedAt(ledger.now());
        reservations.save(reservation);
        for (StockMovementEntity movement : movementRepository.findByReservationIdOrderByIdAsc(reservation.getReservationId())) {
            movement.setOrderId(orderId);
            movementRepository.save(movement);
        }
    }

    /** Restores every line whose product still has an item and marks the reservation RELEASED. */
    private void putBack(StockReservationEntity reservation, String reason) {
        Map<Long, StockItemEntity> locked = ledger.lockInOrder(reservation.getLines().stream()
                .map(StockReservationLineEntity::getProductId).toList());
        for (StockReservationLineEntity line : reservation.getLines()) {
            StockItemEntity item = locked.get(line.getProductId());
            if (item == null) {
                log.warn("Reservation {}: product {} has no stock item any more; its {} units are not restored",
                        reservation.getReservationId(), line.getProductId(), line.getQuantity());
            } else if (StockLedger.resulting(item, line.getQuantity()) > Integer.MAX_VALUE) {
                log.error("Reservation {}: restoring {} units of product {} would exceed the supported maximum",
                        reservation.getReservationId(), line.getQuantity(), line.getProductId());
            } else {
                ledger.record(item, new StockLedger.Change(InventoryMovementType.ORDER_RESTORED, line.getQuantity(),
                        null, reservation.getReservationId(), SYSTEM_ACTOR, reason, null));
            }
        }
        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setUpdatedAt(ledger.now());
        reservations.save(reservation);
        log.info("Reservation {} released ({})", reservation.getReservationId(), reason);
    }

    private static ReservationDto toDto(StockReservationEntity reservation) {
        return new ReservationDto(reservation.getReservationId(), reservation.getLines().stream()
                .map(line -> new ReservedLineDto(line.getProductId(), line.getQuantityAfter(), line.getLedgerVersion()))
                .toList());
    }

    static StockItemDto toDto(StockItemEntity item) {
        return new StockItemDto(item.getProductId(), item.getQuantity(), item.getLedgerVersion());
    }

    static InventoryMovementDto toMovementDto(StockMovementEntity movement) {
        return new InventoryMovementDto(movement.getId(), movement.getProductId(), movement.getOrderId(),
                movement.getType(), movement.getDelta(), movement.getQuantityAfter(), movement.getActor(),
                movement.getReason(), movement.getRequestId(), movement.getCreatedAt());
    }

    /**
     * The movement plus the ledger version it left. Movements imported from the backend have no
     * version; they answer 0, which the backend projection never applies.
     */
    private static StockAdjustmentDto toAdjustmentDto(StockMovementEntity movement) {
        return new StockAdjustmentDto(movement.getId(), movement.getProductId(), movement.getOrderId(),
                movement.getType(), movement.getDelta(), movement.getQuantityAfter(), movement.getActor(),
                movement.getReason(), movement.getRequestId(), movement.getCreatedAt(),
                movement.getLedgerVersion() == null ? 0 : movement.getLedgerVersion());
    }

    private static CustomException productNotFound() {
        return new CustomException("Product not found", HttpStatus.NOT_FOUND);
    }
}
