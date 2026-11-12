package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.fulfilment.PickListItemDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.service.notification.CustomerMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Warehouse shipment state machine. Every transition locks the shipment row,
 * validates the transition locally, mirrors it onto the backend order and only
 * then persists it, so a failed backend call leaves no local change.
 *
 * <p>Connection budget (the pool is small): a request holds at most one database
 * connection at a time. Backend reads that need no lock (the order snapshot of a release,
 * pick lists, tracking lookups, reconciliation) run outside transactions; only the mirror
 * call of a transition runs while its row lock, and so its one connection, is held.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    /** lastActor recorded when the shipment follows a cancellation made on the backend. */
    static final String SYSTEM_ACTOR = "system";
    static final int RECONCILE_BATCH = 100;
    static final java.time.Duration ADOPT_AFTER = java.time.Duration.ofMinutes(2);

    private final ShipmentRepository shipmentRepository;
    private final BackendClient backendClient;
    private final NotificationOutbox notificationOutbox;
    private final Clock warehouseClock;
    private final TransactionTemplate transaction;

    public PageDto<OrderDto> queue(int page, int size) {
        return OrderViews.page(backendClient.fulfilmentQueue(page, size));
    }

    /**
     * Releases a PAID order. The shipment row is written first so the unique
     * order constraint serialises concurrent releases; if the backend refuses
     * the AWAITING_PICK transition the row is removed again.
     *
     * <p>The backend accepts a repeated AWAITING_PICK, so a release whose
     * response was lost is safe to retry: an order that is already AWAITING_PICK
     * without a local shipment is adopted. When the backend call itself fails
     * (503, including timeouts) the order is read again and the shipment is kept
     * if the release did reach the backend.
     *
     * <p>The order snapshot is read before the transaction starts, so no connection waits
     * for that call.
     */
    public ShipmentDto create(Long orderId, String actor) {
        InternalOrderDto order = backendClient.getOrder(orderId);
        if (order.status() != OrderStatus.PAID) {
            throw new CustomException("Only PAID orders can be released to the warehouse", HttpStatus.CONFLICT);
        }
        boolean releasable = order.fulfilmentState() == FulfilmentState.NONE
                || order.fulfilmentState() == FulfilmentState.AWAITING_PICK;
        if (!releasable) {
            throw alreadyReleased();
        }
        return transaction.execute(status -> release(order, actor));
    }

    private ShipmentDto release(InternalOrderDto order, String actor) {
        Long orderId = order.id();
        if (shipmentRepository.existsByOrderId(orderId)) {
            throw alreadyReleased();
        }
        ShipmentEntity shipment = shipmentRepository.saveAndFlush(newShipment(order, actor));
        try {
            backendClient.updateFulfilment(orderId, FulfilmentState.AWAITING_PICK);
        } catch (CustomException e) {
            if (e.getHttpStatus() == HttpStatus.SERVICE_UNAVAILABLE && releasedOnBackend(orderId)) {
                log.warn("Release of order {} reached the backend although the call failed; keeping the shipment",
                        orderId);
                return toDto(shipment);
            }
            shipmentRepository.delete(shipment);
            throw e;
        }
        return toDto(shipment);
    }

    private static CustomException alreadyReleased() {
        return new CustomException("Order already has a shipment", HttpStatus.CONFLICT);
    }

    private boolean releasedOnBackend(Long orderId) {
        try {
            return backendClient.getOrder(orderId).fulfilmentState() == FulfilmentState.AWAITING_PICK;
        } catch (CustomException e) {
            return false;
        }
    }

    private static ShipmentEntity newShipment(InternalOrderDto order, String actor) {
        return ShipmentEntity.builder()
                .orderId(order.id())
                .username(order.username())
                .totalUnits(order.totalUnits())
                .status(ShipmentStatus.AWAITING_PICK)
                .createdBy(actor)
                .lastActor(actor)
                .build();
    }

    /**
     * Safety net behind the event feed: follows customer cancellations made on the backend
     * (which does not call back): every AWAITING_PICK shipment whose order is now CANCELLED becomes CANCELLED.
     * The orders are read outside any transaction; each cancellation then locks and
     * re-checks its shipment in its own short transaction.
     *
     * @return the number of shipments cancelled
     */
    public int reconcileCancellations() {
        List<ShipmentEntity> awaiting = shipmentRepository.findByStatus(ShipmentStatus.AWAITING_PICK,
                PageRequest.of(0, RECONCILE_BATCH, Sort.by("id"))).getContent();
        if (awaiting.isEmpty()) {
            return 0;
        }
        Map<Long, InternalOrderDto> orders = new HashMap<>();
        backendClient.getOrders(awaiting.stream().map(ShipmentEntity::getOrderId).toList())
                .forEach(order -> orders.put(order.id(), order));
        int cancelled = 0;
        for (ShipmentEntity candidate : awaiting) {
            InternalOrderDto order = orders.get(candidate.getOrderId());
            if (order == null || order.fulfilmentState() != FulfilmentState.CANCELLED) {
                continue;
            }
            if (Boolean.TRUE.equals(transaction.execute(status -> cancelIfAwaitingPick(candidate.getId(), order)))) {
                log.info("Shipment {} cancelled because order {} was cancelled", candidate.getId(), order.id());
                cancelled++;
            }
        }
        return cancelled;
    }

    private boolean cancelIfAwaitingPick(Long shipmentId, InternalOrderDto order) {
        ShipmentEntity shipment = lock(shipmentId);
        return shipment.getStatus() == ShipmentStatus.AWAITING_PICK && markCancelledIfOrderCancelled(shipment, order);
    }

    /**
     * Follows an {@code order.cancelled} event from the backend feed: the order's shipment
     * becomes CANCELLED (lastActor {@code system}) when it is still AWAITING_PICK or PICKED.
     * The backend is not called, because the order is already cancelled there. PACKED and
     * SHIPPED shipments are left alone (the backend refuses those cancellations, so such
     * an event points to a race worth logging); unknown orders and finished shipments are
     * ignored.
     *
     * @return whether a shipment was cancelled
     */
    @Transactional
    public boolean followOrderCancellation(Long orderId) {
        Optional<ShipmentEntity> candidate = shipmentRepository.findByOrderId(orderId);
        if (candidate.isEmpty()) {
            log.debug("Order {} was cancelled before it reached the warehouse", orderId);
            return false;
        }
        ShipmentEntity shipment = lock(candidate.get().getId());
        switch (shipment.getStatus()) {
            case AWAITING_PICK, PICKED -> {
                followCancellation(shipment);
                log.info("Shipment {} cancelled because order {} was cancelled", shipment.getId(), orderId);
                return true;
            }
            case PACKED, SHIPPED -> log.warn("Order {} was cancelled but shipment {} is already {}; left unchanged",
                    orderId, shipment.getId(), shipment.getStatus());
            default -> log.debug("Order {} was cancelled; shipment {} is already {}",
                    orderId, shipment.getId(), shipment.getStatus());
        }
        return false;
    }

    /**
     * Creates the missing shipment for orders the backend reports as AWAITING_PICK
     * although no shipment exists (a release whose local write was lost). Orders
     * younger than {@link #ADOPT_AFTER} are left alone, because their release may
     * still be in flight.
     *
     * @return the number of shipments created
     */
    public int adoptReleasedOrders() {
        LocalDateTime cutoff = now().minus(ADOPT_AFTER);
        int adopted = 0;
        for (InternalOrderDto order : backendClient.ordersAwaitingPick(0, RECONCILE_BATCH).getContent()) {
            if (order.updatedAt() == null || order.updatedAt().isAfter(cutoff)
                    || shipmentRepository.existsByOrderId(order.id())) {
                continue;
            }
            try {
                shipmentRepository.saveAndFlush(newShipment(order, SYSTEM_ACTOR));
                log.warn("Adopted order {}: it was released to the warehouse without a shipment", order.id());
                adopted++;
            } catch (DataIntegrityViolationException e) {
                log.debug("Order {} got its shipment concurrently", order.id());
            }
        }
        return adopted;
    }

    @Transactional(readOnly = true)
    public Page<ShipmentDto> list(ShipmentStatus status, Pageable pageable) {
        Page<ShipmentEntity> page = status == null
                ? shipmentRepository.findAll(pageable)
                : shipmentRepository.findByStatus(status, pageable);
        return page.map(this::toDto);
    }

    /**
     * Returns one shipment as stored locally. Reads never change state: customer
     * cancellations arrive through the backend event feed ({@code EventFeedPoller})
     * and, as a safety net, through {@code ShipmentReconciler}.
     */
    @Transactional(readOnly = true)
    public ShipmentDto get(Long id) {
        return toDto(find(id));
    }

    /** Reads the shipment, then the order from the backend without holding a connection. */
    public PickListDto pickList(Long id) {
        ShipmentEntity shipment = find(id);
        InternalOrderDto order = backendClient.getOrder(shipment.getOrderId());
        Map<Long, PickListItemDto> byProduct = new LinkedHashMap<>();
        for (InternalOrderItemDto item : order.items()) {
            byProduct.merge(item.productId(),
                    PickListItemDto.builder()
                            .productId(item.productId())
                            .productName(item.productName())
                            .category(item.category())
                            .quantity(item.quantity())
                            .build(),
                    (left, right) -> {
                        left.setQuantity(left.getQuantity() + right.getQuantity());
                        return left;
                    });
        }
        List<PickListItemDto> items = byProduct.values().stream()
                .sorted(Comparator.comparing((PickListItemDto i) -> lower(i.getCategory()))
                        .thenComparing(i -> lower(i.getProductName())))
                .toList();
        return PickListDto.builder()
                .shipmentId(shipment.getId())
                .orderId(shipment.getOrderId())
                .status(shipment.getStatus())
                .totalUnits(items.stream().mapToInt(PickListItemDto::getQuantity).sum())
                .shippingAddress(OrderViews.address(order.shippingAddress()))
                .items(items)
                .build();
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto pick(Long id, String actor) {
        ShipmentEntity shipment = lock(id);
        require(shipment, "pick", ShipmentStatus.AWAITING_PICK);
        mirror(shipment, FulfilmentState.PICKED);
        shipment.setStatus(ShipmentStatus.PICKED);
        shipment.setPickedAt(now());
        return save(shipment, actor);
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto pack(Long id, BigDecimal weightKg, String actor) {
        ShipmentEntity shipment = lock(id);
        require(shipment, "pack", ShipmentStatus.PICKED);
        mirror(shipment, FulfilmentState.PACKED);
        shipment.setWeightKg(weightKg);
        shipment.setStatus(ShipmentStatus.PACKED);
        shipment.setPackedAt(now());
        return save(shipment, actor);
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto ship(Long id, Carrier carrier, String rawTrackingNumber, String actor) {
        return ship(id, carrier, rawTrackingNumber, false, actor);
    }

    /**
     * PACKED to SHIPPED. {@code simulated} marks a hand-over to the simulated carrier
     * (the tracking number was generated by this service).
     */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto ship(Long id, Carrier carrier, String rawTrackingNumber, boolean simulated, String actor) {
        String trackingNumber = rawTrackingNumber.trim().toUpperCase(Locale.ROOT);
        if (!carrier.acceptsTrackingNumber(trackingNumber)) {
            throw new CustomException(
                    "Tracking number for " + carrier + " must be " + carrier.trackingFormat(), HttpStatus.BAD_REQUEST);
        }
        ShipmentEntity shipment = lock(id);
        require(shipment, "ship", ShipmentStatus.PACKED);
        mirror(shipment, FulfilmentState.SHIPPED);
        shipment.setCarrier(carrier);
        shipment.setTrackingNumber(trackingNumber);
        shipment.setSimulated(simulated);
        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setShippedAt(now());
        ShipmentDto result = save(shipment, actor);
        notificationOutbox.enqueue(CustomerMessages.shipped(shipment));
        return result;
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto deliver(Long id, String actor) {
        ShipmentEntity shipment = lock(id);
        require(shipment, "deliver", ShipmentStatus.SHIPPED);
        mirror(shipment, FulfilmentState.DELIVERED);
        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(now());
        ShipmentDto result = save(shipment, actor);
        notificationOutbox.enqueue(CustomerMessages.delivered(shipment));
        return result;
    }

    /** Cancels the order on the backend (which restores stock and coupons), then the shipment. */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto cancel(Long id, String actor) {
        ShipmentEntity shipment = lock(id);
        require(shipment, "cancel", ShipmentStatus.AWAITING_PICK, ShipmentStatus.PICKED);
        try {
            backendClient.warehouseCancel(shipment.getOrderId());
        } catch (CustomException e) {
            if (e.getHttpStatus() != HttpStatus.CONFLICT
                    || backendClient.getOrder(shipment.getOrderId()).fulfilmentState() != FulfilmentState.CANCELLED) {
                throw e;
            }
            // Already cancelled on the backend: a retried cancel whose first response was
            // lost, or a customer cancellation. Either way the shipment ends CANCELLED.
        }
        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setCancelledAt(now());
        return save(shipment, actor);
    }

    /**
     * Customers may track their own orders and administrators any order. A
     * released order is answered from the local shipment; otherwise the
     * backend decides whether the order exists for the caller (outside any transaction).
     */
    public ShipmentDto trackOrder(Long orderId, String username, boolean admin) {
        Optional<ShipmentEntity> shipment = shipmentRepository.findByOrderId(orderId);
        if (shipment.isPresent()) {
            if (admin || shipment.get().getUsername().equals(username)) {
                return toDto(shipment.get());
            }
            throw orderNotFound();
        }
        InternalOrderDto order = backendClient.getOrder(orderId);
        if (!admin && !order.username().equals(username)) {
            throw orderNotFound();
        }
        throw new CustomException("Shipment not found", HttpStatus.NOT_FOUND);
    }

    private void mirror(ShipmentEntity shipment, FulfilmentState state) {
        try {
            backendClient.updateFulfilment(shipment.getOrderId(), state);
        } catch (CustomException e) {
            reconcileOnConflict(shipment, e);
            throw e;
        }
    }

    /**
     * A 409 from the backend usually means the customer cancelled the order
     * while it was awaiting pick. In that case the local shipment follows the
     * order into CANCELLED (committed despite the error) and the caller gets
     * 409 "Order was cancelled by the customer"; any other conflict is
     * reported unchanged.
     */
    private void reconcileOnConflict(ShipmentEntity shipment, CustomException failure) {
        if (failure.getHttpStatus() != HttpStatus.CONFLICT) {
            return;
        }
        if (markCancelledIfOrderCancelled(shipment, backendClient.getOrder(shipment.getOrderId()))) {
            throw new ShipmentCancelledException();
        }
    }

    private boolean markCancelledIfOrderCancelled(ShipmentEntity shipment, InternalOrderDto order) {
        if (order.fulfilmentState() != FulfilmentState.CANCELLED) {
            return false;
        }
        followCancellation(shipment);
        return true;
    }

    /** The shipment follows a cancellation made on the backend (reconciler, refused transition, event feed). */
    private void followCancellation(ShipmentEntity shipment) {
        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setCancelledAt(now());
        shipment.setLastActor(SYSTEM_ACTOR);
        shipmentRepository.saveAndFlush(shipment);
    }

    private ShipmentEntity lock(Long id) {
        return shipmentRepository.findByIdForUpdate(id).orElseThrow(ShipmentService::shipmentNotFound);
    }

    private ShipmentEntity find(Long id) {
        return shipmentRepository.findById(id).orElseThrow(ShipmentService::shipmentNotFound);
    }

    private static CustomException shipmentNotFound() {
        return new CustomException("Shipment not found", HttpStatus.NOT_FOUND);
    }

    private static CustomException orderNotFound() {
        return new CustomException("Order not found", HttpStatus.NOT_FOUND);
    }

    private static void require(ShipmentEntity shipment, String action, ShipmentStatus... allowed) {
        if (!Set.of(allowed).contains(shipment.getStatus())) {
            throw new CustomException(
                    "Cannot " + action + " a shipment in status " + shipment.getStatus(), HttpStatus.CONFLICT);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private ShipmentDto save(ShipmentEntity shipment, String actor) {
        shipment.setLastActor(actor);
        return toDto(shipmentRepository.saveAndFlush(shipment));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(warehouseClock);
    }

    private ShipmentDto toDto(ShipmentEntity shipment) {
        return ShipmentDto.builder()
                .id(shipment.getId())
                .orderId(shipment.getOrderId())
                .username(shipment.getUsername())
                .status(shipment.getStatus())
                .totalUnits(shipment.getTotalUnits())
                .carrier(shipment.getCarrier())
                .trackingNumber(shipment.getTrackingNumber())
                .weightKg(shipment.getWeightKg())
                .createdBy(shipment.getCreatedBy())
                .lastActor(shipment.getLastActor())
                .createdAt(shipment.getCreatedAt())
                .updatedAt(shipment.getUpdatedAt())
                .pickedAt(shipment.getPickedAt())
                .packedAt(shipment.getPackedAt())
                .shippedAt(shipment.getShippedAt())
                .deliveredAt(shipment.getDeliveredAt())
                .cancelledAt(shipment.getCancelledAt())
                .build();
    }
}
