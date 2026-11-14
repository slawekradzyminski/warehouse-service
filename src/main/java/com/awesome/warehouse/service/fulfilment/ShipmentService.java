package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.fulfilment.PickListItemDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentLineEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.returns.OrderLockEntity;
import com.awesome.warehouse.repository.fulfilment.ShipmentLineRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.service.notification.CustomerMessages;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import com.awesome.warehouse.service.returns.OrderLocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Warehouse parcel state machine. An order released to the warehouse is split into one
 * parcel (shipment) per location its lines were allocated to; each parcel is picked,
 * packed, shipped and delivered on its own. The backend keeps one fulfilment state per
 * order, so every transition mirrors the order's {@link FulfilmentAggregate aggregate}
 * (forward-only) rather than the parcel's own state.
 *
 * <p>Locking: every write to an order's parcels first locks the order's row in
 * {@code order_locks} (see {@link OrderLocks}), then the parcel rows it changes. With that
 * one lock per order, two parcels of the same order never compute or mirror the aggregate
 * concurrently, a release and an adoption of the same order never both create parcels, and
 * a customer cancellation arriving through the feed waits for an in-flight transition.
 * Each transition validates locally, mirrors to the backend, then persists, so a failed
 * backend call leaves no local change.
 *
 * <p>Connection budget (the pool is small): a request holds at most one database
 * connection at a time. Backend reads that need no lock (the order snapshot of a release,
 * pick lists, tracking lookups, reconciliation) run outside transactions; only the mirror
 * call of a transition runs while the order lock, and so its one connection, is held.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    /** lastActor recorded when the shipment follows a cancellation made on the backend. */
    static final String SYSTEM_ACTOR = "system";
    static final int RECONCILE_BATCH = 100;
    static final Duration ADOPT_AFTER = Duration.ofMinutes(2);
    private static final Set<ShipmentStatus> CANCELLABLE = EnumSet.of(ShipmentStatus.AWAITING_PICK, ShipmentStatus.PICKED);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentLineRepository lineRepository;
    private final WarehouseAllocation allocation;
    private final LegacyShipmentLines legacyLines;
    private final ShipmentViews views;
    private final OrderLocks orderLocks;
    private final BackendClient backendClient;
    private final NotificationOutbox notificationOutbox;
    private final Clock warehouseClock;
    private final TransactionTemplate transaction;

    /** The order's lock row, the locked parcel and all parcels of the order (oldest first). */
    record LockedOrder(OrderLockEntity lock, ShipmentEntity parcel, List<ShipmentEntity> parcels) {
    }

    public PageDto<OrderDto> queue(int page, int size) {
        return OrderViews.page(backendClient.fulfilmentQueue(page, size));
    }

    /**
     * Releases a PAID order: allocates its lines to locations and creates one AWAITING_PICK
     * parcel per location. Lines no active location stocks fail the release with 409 before
     * anything is written. The parcels are written under the order lock, then the backend is
     * moved to AWAITING_PICK; if the backend refuses, the transaction (and so every parcel)
     * is rolled back.
     *
     * <p>The backend accepts a repeated AWAITING_PICK, so a release whose response was lost
     * is safe to retry: an order that is already AWAITING_PICK without a local shipment is
     * adopted. When the backend call itself fails (503, including timeouts) the order is read
     * again and the parcels are kept if the release did reach the backend.
     *
     * <p>The order snapshot is read before the transaction starts, so no connection waits
     * for that call.
     *
     * @return the first parcel (with {@code parcelCount})
     */
    public ShipmentDto create(Long orderId, String actor) {
        InternalOrderDto order = backendClient.getOrder(orderId);
        if (order.status() != OrderStatus.PAID) {
            throw new CustomException("Only PAID orders can be released to the warehouse", HttpStatus.CONFLICT);
        }
        boolean releasable = order.fulfilmentState() == FulfilmentState.NONE
                || order.fulfilmentState() == FulfilmentState.AWAITING_PICK;
        if (!releasable || shipmentRepository.existsByOrderId(orderId)) {
            throw alreadyReleased();
        }
        allocation.allocate(order);
        orderLocks.ensureExists(orderId);
        return transaction.execute(status -> release(order, actor));
    }

    private ShipmentDto release(InternalOrderDto order, String actor) {
        Long orderId = order.id();
        OrderLockEntity lock = orderLocks.lockInTransaction(orderId);
        if (shipmentRepository.existsByOrderId(orderId)) {
            throw alreadyReleased();
        }
        List<ShipmentEntity> parcels = createParcels(order, allocation.allocate(order), actor);
        lock.setMirroredState(FulfilmentState.AWAITING_PICK);
        try {
            backendClient.updateFulfilment(orderId, FulfilmentState.AWAITING_PICK);
        } catch (CustomException e) {
            if (e.getHttpStatus() == HttpStatus.SERVICE_UNAVAILABLE && releasedOnBackend(orderId)) {
                log.warn("Release of order {} reached the backend although the call failed; keeping the parcels",
                        orderId);
                return views.toDto(parcels.getFirst());
            }
            throw e;
        }
        return views.toDto(parcels.getFirst());
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

    private List<ShipmentEntity> createParcels(InternalOrderDto order, Map<String, List<ShipmentAllocator.Line>> plan,
                                               String actor) {
        List<ShipmentEntity> parcels = new ArrayList<>();
        plan.forEach((locationCode, lines) -> {
            ShipmentEntity parcel = shipmentRepository.saveAndFlush(ShipmentEntity.builder()
                    .orderId(order.id())
                    .username(order.username())
                    .locationCode(locationCode)
                    .totalUnits(lines.stream().mapToInt(ShipmentAllocator.Line::quantity).sum())
                    .status(ShipmentStatus.AWAITING_PICK)
                    .createdBy(actor)
                    .lastActor(actor)
                    .build());
            for (ShipmentAllocator.Line line : lines) {
                lineRepository.save(ShipmentLineEntity.builder()
                        .shipmentId(parcel.getId())
                        .productId(line.productId())
                        .productName(line.productName())
                        .category(line.category())
                        .quantity(line.quantity())
                        .build());
            }
            parcels.add(parcel);
        });
        return parcels;
    }

    /**
     * Safety net behind the event feed: follows customer cancellations made on the backend
     * (which does not call back): every AWAITING_PICK or PICKED parcel of an order with an
     * AWAITING_PICK parcel whose order is now CANCELLED becomes CANCELLED. The orders are read
     * outside any transaction; each order is then cancelled under its lock in its own short
     * transaction.
     *
     * @return the number of parcels cancelled
     */
    public int reconcileCancellations() {
        List<ShipmentEntity> awaiting = shipmentRepository.findByStatus(ShipmentStatus.AWAITING_PICK,
                PageRequest.of(0, RECONCILE_BATCH, Sort.by("id"))).getContent();
        if (awaiting.isEmpty()) {
            return 0;
        }
        Set<Long> orderIds = new LinkedHashSet<>();
        awaiting.forEach(parcel -> orderIds.add(parcel.getOrderId()));
        int cancelled = 0;
        for (InternalOrderDto order : backendClient.getOrders(orderIds)) {
            if (order.fulfilmentState() == FulfilmentState.CANCELLED) {
                Integer count = transaction.execute(status -> followCancellationLocked(order.id()));
                cancelled += count == null ? 0 : count;
            }
        }
        return cancelled;
    }

    /**
     * Follows an {@code order.cancelled} event from the backend feed: the order's
     * AWAITING_PICK and PICKED parcels become CANCELLED (lastActor {@code system}). The backend
     * is not called, because the order is already cancelled there. PACKED and SHIPPED parcels
     * are left alone (the backend refuses those cancellations, so such an event points to a
     * race worth logging); unknown orders and finished parcels are ignored.
     *
     * @return whether a parcel was cancelled
     */
    @Transactional
    public boolean followOrderCancellation(Long orderId) {
        if (!shipmentRepository.existsByOrderId(orderId)) {
            log.debug("Order {} was cancelled before it reached the warehouse", orderId);
            return false;
        }
        return followCancellationLocked(orderId) > 0;
    }

    private int followCancellationLocked(Long orderId) {
        OrderLockEntity lock = orderLocks.lockInTransaction(orderId);
        int cancelled = 0;
        for (ShipmentEntity parcel : shipmentRepository.findByOrderIdOrderByIdAsc(orderId)) {
            switch (parcel.getStatus()) {
                case AWAITING_PICK, PICKED -> {
                    followCancellation(parcel);
                    log.info("Shipment {} cancelled because order {} was cancelled", parcel.getId(), orderId);
                    cancelled++;
                }
                case PACKED, SHIPPED -> log.warn("Order {} was cancelled but shipment {} is already {}; left unchanged",
                        orderId, parcel.getId(), parcel.getStatus());
                default -> log.debug("Order {} was cancelled; shipment {} is already {}",
                        orderId, parcel.getId(), parcel.getStatus());
            }
        }
        if (cancelled > 0) {
            lock.setMirroredState(FulfilmentState.CANCELLED);
        }
        return cancelled;
    }

    /**
     * Creates the missing parcels for orders the backend reports as AWAITING_PICK although
     * no shipment exists (a release whose local write was lost). Orders younger than
     * {@link #ADOPT_AFTER} are left alone, because their release may still be in flight; an
     * order is allocated and its parcels written under the order lock after re-checking that
     * no parcel exists, so a concurrent release or adoption creates them once. The backend is
     * not called: it already shows AWAITING_PICK. An order no active location can take is
     * logged and retried on the next run.
     *
     * @return the number of orders adopted
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
                orderLocks.ensureExists(order.id());
                if (Boolean.TRUE.equals(transaction.execute(status -> adopt(order)))) {
                    log.warn("Adopted order {}: it was released to the warehouse without a shipment", order.id());
                    adopted++;
                }
            } catch (DataIntegrityViolationException e) {
                log.debug("Order {} got its shipment concurrently", order.id());
            } catch (CustomException e) {
                log.warn("Order {} cannot be adopted: {}", order.id(), e.getMessage());
            }
        }
        return adopted;
    }

    private boolean adopt(InternalOrderDto order) {
        OrderLockEntity lock = orderLocks.lockInTransaction(order.id());
        if (shipmentRepository.existsByOrderId(order.id())) {
            return false;
        }
        createParcels(order, allocation.allocate(order), SYSTEM_ACTOR);
        lock.setMirroredState(FulfilmentAggregate.target(lock.getMirroredState(), FulfilmentState.AWAITING_PICK));
        return true;
    }

    /** Parcels oldest first, optionally by status. Lines of pre-1.5.0 parcels are filled first. */
    public Page<ShipmentDto> list(ShipmentStatus status, Pageable pageable) {
        Page<ShipmentEntity> page = status == null
                ? shipmentRepository.findAll(pageable)
                : shipmentRepository.findByStatus(status, pageable);
        legacyLines.fill(page.getContent());
        Map<Long, ShipmentDto> byId = new HashMap<>();
        views.toDtos(page.getContent()).forEach(dto -> byId.put(dto.getId(), dto));
        return page.map(parcel -> byId.get(parcel.getId()));
    }

    /**
     * Returns one parcel as stored locally. Reads never change a parcel's state: customer
     * cancellations arrive through the backend event feed ({@code EventFeedPoller}) and, as
     * a safety net, through {@code ShipmentReconciler}. The only write a read may cause is
     * filling the lines of a pre-1.5.0 parcel ({@link LegacyShipmentLines}).
     */
    public ShipmentDto get(Long id) {
        ShipmentEntity parcel = find(id);
        legacyLines.fill(List.of(parcel));
        return views.toDto(parcel);
    }

    /**
     * The parcel's lines grouped by product and sorted by category, then name, with the
     * order's shipping address (read from the backend without holding a connection).
     */
    public PickListDto pickList(Long id) {
        ShipmentEntity shipment = find(id);
        InternalOrderDto order = backendClient.getOrder(shipment.getOrderId());
        List<PickListItemDto> items = (shipment.isLinesPending()
                ? WarehouseAllocation.lines(order).stream()
                        .map(line -> item(line.productId(), line.productName(), line.category(), line.quantity()))
                : lineRepository.findByShipmentIdOrderByIdAsc(id).stream()
                        .map(line -> item(line.getProductId(), line.getProductName(), line.getCategory(),
                                line.getQuantity())))
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

    private static PickListItemDto item(Long productId, String name, String category, int quantity) {
        return PickListItemDto.builder().productId(productId).productName(name).category(category).quantity(quantity)
                .build();
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto pick(Long id, String actor) {
        return transition(id, "pick", ShipmentStatus.AWAITING_PICK, ShipmentStatus.PICKED, actor,
                parcel -> parcel.setPickedAt(now()));
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto pack(Long id, BigDecimal weightKg, String actor) {
        return transition(id, "pack", ShipmentStatus.PICKED, ShipmentStatus.PACKED, actor, parcel -> {
            parcel.setWeightKg(weightKg);
            parcel.setPackedAt(now());
        });
    }

    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto ship(Long id, Carrier carrier, String rawTrackingNumber, String actor) {
        return ship(id, carrier, rawTrackingNumber, false, actor);
    }

    /**
     * PACKED to SHIPPED. {@code simulated} marks a hand-over to the simulated carrier
     * (the tracking number was generated by this service). Every parcel's hand-over emails
     * the customer its tracking number.
     */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto ship(Long id, Carrier carrier, String rawTrackingNumber, boolean simulated, String actor) {
        String trackingNumber = rawTrackingNumber.trim().toUpperCase(Locale.ROOT);
        if (!carrier.acceptsTrackingNumber(trackingNumber)) {
            throw new CustomException(
                    "Tracking number for " + carrier + " must be " + carrier.trackingFormat(), HttpStatus.BAD_REQUEST);
        }
        ShipmentDto result = transition(id, "ship", ShipmentStatus.PACKED, ShipmentStatus.SHIPPED, actor, parcel -> {
            parcel.setCarrier(carrier);
            parcel.setTrackingNumber(trackingNumber);
            parcel.setSimulated(simulated);
            parcel.setShippedAt(now());
        });
        notificationOutbox.enqueue(CustomerMessages.shipped(find(id)));
        return result;
    }

    /** SHIPPED to DELIVERED. The customer is emailed once, when the order's last parcel is delivered. */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto deliver(Long id, String actor) {
        LockedOrder order = lockOrderOf(id);
        FulfilmentState before = order.lock().getMirroredState();
        ShipmentDto result = transition(order, "deliver", ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED, actor,
                parcel -> parcel.setDeliveredAt(now()));
        if (before != FulfilmentState.DELIVERED && order.lock().getMirroredState() == FulfilmentState.DELIVERED) {
            notificationOutbox.enqueue(CustomerMessages.delivered(order.parcel()));
        }
        return result;
    }

    /**
     * Cancels the order on the backend (which restores stock and coupons), then every
     * AWAITING_PICK or PICKED parcel of it. The backend only cancels whole orders, so the
     * cancellation is refused while another parcel of the order is already packed or later.
     */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto cancel(Long id, String actor) {
        LockedOrder order = lockOrderOf(id);
        ShipmentEntity shipment = order.parcel();
        require(shipment, "cancel", CANCELLABLE.toArray(ShipmentStatus[]::new));
        for (ShipmentEntity sibling : order.parcels()) {
            if (sibling.getStatus() != ShipmentStatus.CANCELLED && !CANCELLABLE.contains(sibling.getStatus())) {
                throw new CustomException("Cannot cancel order " + shipment.getOrderId() + ": its parcel from "
                        + sibling.getLocationCode() + " is already " + sibling.getStatus(), HttpStatus.CONFLICT);
            }
        }
        try {
            backendClient.warehouseCancel(shipment.getOrderId());
        } catch (CustomException e) {
            if (e.getHttpStatus() != HttpStatus.CONFLICT
                    || backendClient.getOrder(shipment.getOrderId()).fulfilmentState() != FulfilmentState.CANCELLED) {
                throw e;
            }
            // Already cancelled on the backend: a retried cancel whose first response was
            // lost, or a customer cancellation. Either way the parcels end CANCELLED.
        }
        for (ShipmentEntity parcel : order.parcels()) {
            if (CANCELLABLE.contains(parcel.getStatus())) {
                parcel.setStatus(ShipmentStatus.CANCELLED);
                parcel.setCancelledAt(now());
                parcel.setLastActor(actor);
                shipmentRepository.saveAndFlush(parcel);
            }
        }
        order.lock().setMirroredState(FulfilmentState.CANCELLED);
        return views.toDto(shipment);
    }

    /**
     * The order's first parcel. Customers may track their own orders and administrators any
     * order. A released order is answered locally; otherwise the backend decides whether the
     * order exists for the caller (outside any transaction).
     */
    public ShipmentDto trackOrder(Long orderId, String username, boolean admin) {
        List<ShipmentDto> parcels = orderShipments(orderId, username, admin);
        if (parcels.isEmpty()) {
            throw new CustomException("Shipment not found", HttpStatus.NOT_FOUND);
        }
        return parcels.getFirst();
    }

    /**
     * All parcels of an order, oldest first; empty when the caller's order is not released
     * yet. Unknown orders and other customers' orders are 404 "Order not found".
     */
    public List<ShipmentDto> orderShipments(Long orderId, String username, boolean admin) {
        List<ShipmentEntity> parcels = shipmentRepository.findByOrderIdOrderByIdAsc(orderId);
        if (!parcels.isEmpty()) {
            if (!admin && !parcels.getFirst().getUsername().equals(username)) {
                throw orderNotFound();
            }
            legacyLines.fill(parcels);
            return views.toDtos(parcels);
        }
        InternalOrderDto order = backendClient.getOrder(orderId);
        if (!admin && !order.username().equals(username)) {
            throw orderNotFound();
        }
        return List.of();
    }

    /**
     * Locks the parcel for a write: the order's lock first, then the parcel row, the order
     * in which every fulfilment write takes them. Must run inside a transaction.
     */
    public ShipmentEntity lockForUpdate(Long id) {
        return lockOrderOf(id).parcel();
    }

    LockedOrder lockOrderOf(Long id) {
        // Only the order id is read before the locks: an entity loaded here would stay in the
        // persistence context, and the locking query below would return it without refreshing.
        Long orderId = shipmentRepository.findOrderIdById(id).orElseThrow(ShipmentService::shipmentNotFound);
        OrderLockEntity lock = orderLocks.lockInTransaction(orderId);
        ShipmentEntity parcel = shipmentRepository.findByIdForUpdate(id).orElseThrow(ShipmentService::shipmentNotFound);
        return new LockedOrder(lock, parcel, shipmentRepository.findByOrderIdOrderByIdAsc(orderId));
    }

    private ShipmentDto transition(Long id, String action, ShipmentStatus from, ShipmentStatus to, String actor,
                                   Consumer<ShipmentEntity> apply) {
        return transition(lockOrderOf(id), action, from, to, actor, apply);
    }

    private ShipmentDto transition(LockedOrder order, String action, ShipmentStatus from, ShipmentStatus to,
                                   String actor, Consumer<ShipmentEntity> apply) {
        ShipmentEntity parcel = order.parcel();
        require(parcel, action, from);
        mirror(order, to);
        parcel.setStatus(to);
        apply.accept(parcel);
        parcel.setLastActor(actor);
        return views.toDto(shipmentRepository.saveAndFlush(parcel));
    }

    /**
     * Mirrors the order's aggregate as it will be once the locked parcel is in {@code next}:
     * {@code max(last mirrored, aggregate)}, one backend step at a time. Nothing is called
     * when the aggregate does not move forward (for example picking the second parcel of an
     * order that is already PICKED). If a later step fails, the earlier ones stay on the
     * backend while the local change rolls back; the retry repeats them, which the backend
     * accepts as no-ops.
     */
    private void mirror(LockedOrder order, ShipmentStatus next) {
        List<ShipmentStatus> statuses = order.parcels().stream()
                .map(parcel -> parcel.getId().equals(order.parcel().getId()) ? next : parcel.getStatus())
                .toList();
        FulfilmentState last = order.lock().getMirroredState();
        FulfilmentState target = FulfilmentAggregate.target(last, FulfilmentAggregate.aggregate(statuses));
        for (FulfilmentState step : FulfilmentAggregate.steps(last, target)) {
            try {
                backendClient.updateFulfilment(order.parcel().getOrderId(), step);
            } catch (CustomException e) {
                reconcileOnConflict(order, e);
                throw e;
            }
        }
        order.lock().setMirroredState(target);
    }

    /**
     * A 409 from the backend usually means the customer cancelled the order while it was
     * awaiting pick. In that case the order's parcels follow it into CANCELLED (committed
     * despite the error) and the caller gets 409 "Order was cancelled by the customer"; any
     * other conflict is reported unchanged.
     */
    private void reconcileOnConflict(LockedOrder order, CustomException failure) {
        if (failure.getHttpStatus() != HttpStatus.CONFLICT) {
            return;
        }
        if (backendClient.getOrder(order.parcel().getOrderId()).fulfilmentState() == FulfilmentState.CANCELLED) {
            for (ShipmentEntity parcel : order.parcels()) {
                if (CANCELLABLE.contains(parcel.getStatus())) {
                    followCancellation(parcel);
                }
            }
            order.lock().setMirroredState(FulfilmentState.CANCELLED);
            throw new ShipmentCancelledException();
        }
    }

    /** The parcel follows a cancellation made on the backend (reconciler, refused transition, event feed). */
    private void followCancellation(ShipmentEntity shipment) {
        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setCancelledAt(now());
        shipment.setLastActor(SYSTEM_ACTOR);
        shipmentRepository.saveAndFlush(shipment);
    }

    private ShipmentEntity find(Long id) {
        return shipmentRepository.findById(id).orElseThrow(ShipmentService::shipmentNotFound);
    }

    static CustomException shipmentNotFound() {
        return new CustomException("Shipment not found", HttpStatus.NOT_FOUND);
    }

    private static CustomException orderNotFound() {
        return new CustomException("Order not found", HttpStatus.NOT_FOUND);
    }

    static void require(ShipmentEntity shipment, String action, ShipmentStatus... allowed) {
        if (!Set.of(allowed).contains(shipment.getStatus())) {
            throw new CustomException(
                    "Cannot " + action + " a shipment in status " + shipment.getStatus(), HttpStatus.CONFLICT);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(warehouseClock);
    }
}
