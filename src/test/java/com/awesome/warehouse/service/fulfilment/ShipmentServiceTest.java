package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalAddressDto;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentLineEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.returns.OrderLockEntity;
import com.awesome.warehouse.repository.fulfilment.ShipmentLineRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.service.notification.CustomerMessages.CustomerMessage;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import com.awesome.warehouse.service.returns.OrderLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 12, 0);
    private static final CustomException CONFLICT = new CustomException("Fulfilment transition not allowed", HttpStatus.CONFLICT);
    private static final CustomException UNAVAILABLE = new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private ShipmentLineRepository lineRepository;
    @Mock private WarehouseAllocation allocation;
    @Mock private LegacyShipmentLines legacyLines;
    @Mock private ShipmentViews views;
    @Mock private OrderLocks orderLocks;
    @Mock private BackendClient backendClient;
    @Mock private NotificationOutbox notificationOutbox;

    private ShipmentService service;
    private ShipmentEntity shipment;
    private final List<ShipmentEntity> parcels = new ArrayList<>();
    private final OrderLockEntity lock = new OrderLockEntity(42L);
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactions = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        lenient().when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ShipmentService(shipmentRepository, lineRepository, allocation, legacyLines, views, orderLocks,
                backendClient, notificationOutbox, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                new TransactionTemplate(transactions));
        shipment = parcel(7L, "KRK", ShipmentStatus.AWAITING_PICK);
        lock.setMirroredState(FulfilmentState.AWAITING_PICK);
        lenient().when(shipmentRepository.findById(anyLong())).thenAnswer(invocation -> byId(invocation.getArgument(0)));
        lenient().when(shipmentRepository.findOrderIdById(anyLong()))
                .thenAnswer(invocation -> byId(invocation.getArgument(0)).map(ShipmentEntity::getOrderId));
        lenient().when(shipmentRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> byId(invocation.getArgument(0)));
        lenient().when(shipmentRepository.findByOrderIdOrderByIdAsc(42L)).thenAnswer(invocation -> List.copyOf(parcels));
        lenient().when(shipmentRepository.existsByOrderId(42L)).thenAnswer(invocation -> !parcels.isEmpty());
        lenient().when(shipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ShipmentEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(ids.incrementAndGet());
            }
            return saved;
        });
        lenient().when(orderLocks.lockInTransaction(42L)).thenReturn(lock);
        lenient().when(views.toDto(any())).thenAnswer(invocation -> dto(invocation.getArgument(0)));
        lenient().when(views.toDtos(any())).thenAnswer(invocation -> {
            List<ShipmentEntity> given = List.copyOf(invocation.getArgument(0));
            return given.stream().map(ShipmentServiceTest::dto).toList();
        });
    }

    private ShipmentEntity parcel(long id, String location, ShipmentStatus status) {
        ShipmentEntity parcel = ShipmentEntity.builder().id(id).orderId(42L).username("buyer").totalUnits(5)
                .locationCode(location).status(status).build();
        parcels.add(parcel);
        return parcel;
    }

    private Optional<ShipmentEntity> byId(Long id) {
        return parcels.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    private static ShipmentDto dto(ShipmentEntity entity) {
        return ShipmentDto.builder().id(entity.getId()).orderId(entity.getOrderId()).status(entity.getStatus())
                .locationCode(entity.getLocationCode()).trackingNumber(entity.getTrackingNumber())
                .weightKg(entity.getWeightKg()).lastActor(entity.getLastActor()).build();
    }

    private static InternalOrderDto order(OrderStatus status, FulfilmentState state, InternalOrderItemDto... items) {
        return order(status, state, NOW, items);
    }

    private static InternalOrderDto order(OrderStatus status, FulfilmentState state, LocalDateTime updatedAt,
                                          InternalOrderItemDto... items) {
        return new InternalOrderDto(42L, "buyer", status, state, new BigDecimal("10.00"), BigDecimal.ZERO,
                new BigDecimal("10.00"), null, new InternalAddressDto("s", "c", "st", "00-000", "PL"), List.of(items),
                NOW, updatedAt);
    }

    private static InternalOrderItemDto item(long productId, String name, String category, int quantity) {
        return new InternalOrderItemDto(null, productId, name, category, quantity, BigDecimal.ONE);
    }

    private static Map<String, List<ShipmentAllocator.Line>> plan(Object... locationsAndLines) {
        Map<String, List<ShipmentAllocator.Line>> plan = new LinkedHashMap<>();
        for (int i = 0; i < locationsAndLines.length; i += 2) {
            plan.computeIfAbsent((String) locationsAndLines[i], code -> new ArrayList<>())
                    .add((ShipmentAllocator.Line) locationsAndLines[i + 1]);
        }
        return plan;
    }

    // --- transitions and the aggregate mirror ---

    @Test
    void pickTakesTheOrderLockThenTheRowMirrorsThenPersists() {
        ShipmentDto picked = service.pick(7L, "picker");

        InOrder order = inOrder(orderLocks, shipmentRepository, backendClient);
        order.verify(orderLocks).lockInTransaction(42L);
        order.verify(shipmentRepository).findByIdForUpdate(7L);
        order.verify(backendClient).updateFulfilment(42L, FulfilmentState.PICKED);
        order.verify(shipmentRepository).saveAndFlush(shipment);
        assertThat(picked.getStatus()).isEqualTo(ShipmentStatus.PICKED);
        assertThat(shipment.getPickedAt()).isEqualTo(NOW);
        assertThat(shipment.getLastActor()).isEqualTo("picker");
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.PICKED);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "AWAITING_PICK", mode = EnumSource.Mode.EXCLUDE)
    void pickIsOnlyAllowedWhileAwaitingPickAndNeverReachesTheBackend(ShipmentStatus status) {
        shipment.setStatus(status);

        assertThatThrownBy(() -> service.pick(7L, "picker"))
                .isInstanceOf(CustomException.class)
                .hasMessage("Cannot pick a shipment in status " + status)
                .extracting("httpStatus").isEqualTo(HttpStatus.CONFLICT);
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
        verify(shipmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void backendFailureLeavesTheParcelAndTheMirrorUntouched() {
        when(backendClient.updateFulfilment(42L, FulfilmentState.PICKED)).thenThrow(UNAVAILABLE);

        assertThatThrownBy(() -> service.pick(7L, "picker")).isSameAs(UNAVAILABLE);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(shipment.getPickedAt()).isNull();
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.AWAITING_PICK);
        verify(shipmentRepository, never()).saveAndFlush(any());
        verify(backendClient, never()).getOrder(anyLong());
    }

    @Test
    void conflictCausedByACustomerCancellationCancelsEveryUnpackedParcelOfTheOrder() {
        ShipmentEntity other = parcel(8L, "GDN", ShipmentStatus.AWAITING_PICK);
        when(backendClient.updateFulfilment(42L, FulfilmentState.PICKED)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.CANCELLED, FulfilmentState.CANCELLED));

        assertThatThrownBy(() -> service.pick(7L, "picker"))
                .isInstanceOf(ShipmentCancelledException.class)
                .hasMessage("Order was cancelled by the customer");

        assertThat(List.of(shipment, other)).allSatisfy(parcel -> {
            assertThat(parcel.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(parcel.getCancelledAt()).isEqualTo(NOW);
            assertThat(parcel.getLastActor()).isEqualTo("system");
        });
        assertThat(shipment.getPickedAt()).isNull();
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.CANCELLED);
    }

    @Test
    void otherConflictsAreReportedWithoutChangingTheParcel() {
        when(backendClient.updateFulfilment(42L, FulfilmentState.PICKED)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));

        assertThatThrownBy(() -> service.pick(7L, "picker")).isSameAs(CONFLICT);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.AWAITING_PICK);
        verify(shipmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void pickingTheSecondParcelOfAnAlreadyPickedOrderDoesNotCallTheBackend() {
        parcel(8L, "GDN", ShipmentStatus.PICKED);
        lock.setMirroredState(FulfilmentState.PICKED);

        assertThat(service.pick(7L, "picker").getStatus()).isEqualTo(ShipmentStatus.PICKED);

        verify(backendClient, never()).updateFulfilment(anyLong(), any());
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.PICKED);
    }

    @Test
    void theFirstPickOfASplitOrderMirrorsPickedAndLaterStepsWaitForTheSlowestParcel() {
        ShipmentEntity other = parcel(8L, "GDN", ShipmentStatus.AWAITING_PICK);

        service.pick(7L, "picker");
        service.pack(7L, BigDecimal.ONE, "packer");
        service.ship(7L, Carrier.DHL, "1234567890", "packer");
        service.pick(8L, "picker");
        service.pack(8L, BigDecimal.ONE, "packer");

        verify(backendClient, times(1)).updateFulfilment(42L, FulfilmentState.PICKED);
        verify(backendClient, times(1)).updateFulfilment(42L, FulfilmentState.PACKED);
        verify(backendClient, never()).updateFulfilment(42L, FulfilmentState.SHIPPED);
        assertThat(other.getStatus()).isEqualTo(ShipmentStatus.PACKED);
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.PACKED);
    }

    @Test
    void aParcelWithoutAMirroredStateStepsTheBackendForwardOneStateAtATime() {
        lock.setMirroredState(null);

        service.pick(7L, "picker");

        InOrder order = inOrder(backendClient);
        order.verify(backendClient).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);
        order.verify(backendClient).updateFulfilment(42L, FulfilmentState.PICKED);
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.PICKED);
    }

    @Test
    void shippingNormalisesTheTrackingNumberMirrorsAndNotifies() {
        shipment.setStatus(ShipmentStatus.PACKED);
        lock.setMirroredState(FulfilmentState.PACKED);

        ShipmentDto shipped = service.ship(7L, Carrier.UPS, " 1z999aa10123456784 ", "packer");

        verify(backendClient).updateFulfilment(42L, FulfilmentState.SHIPPED);
        assertThat(shipped.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(shipment.getCarrier()).isEqualTo(Carrier.UPS);
        assertThat(shipment.getShippedAt()).isEqualTo(NOW);
        assertThat(shipment.isSimulated()).isFalse();
        ArgumentCaptor<CustomerMessage> message = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(notificationOutbox).enqueue(message.capture());
        assertThat(message.getValue().template()).isEqualTo(NotificationTemplate.ORDER_SHIPPED);
        assertThat(message.getValue().properties()).containsEntry("trackingNumber", "1Z999AA10123456784");
    }

    @Test
    void onlyAHandOverToTheSimulatedCarrierMarksTheParcelSimulated() {
        shipment.setStatus(ShipmentStatus.PACKED);
        lock.setMirroredState(FulfilmentState.PACKED);

        service.ship(7L, Carrier.DHL, "1234567890", true, "packer");

        assertThat(shipment.isSimulated()).isTrue();
    }

    @Test
    void invalidTrackingNumbersAreRejectedBeforeAnyLockOrBackendCall() {
        shipment.setStatus(ShipmentStatus.PACKED);

        assertThatThrownBy(() -> service.ship(7L, Carrier.INPOST, "123", "packer"))
                .isInstanceOf(CustomException.class)
                .hasMessage("Tracking number for INPOST must be 24 digits")
                .extracting("httpStatus").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderLocks, never()).lockInTransaction(anyLong());
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
    }

    @Test
    void deliveringTheOnlyParcelDeliversTheOrderAndNotifiesOnce() {
        shipment.setStatus(ShipmentStatus.SHIPPED);
        lock.setMirroredState(FulfilmentState.SHIPPED);

        ShipmentDto delivered = service.deliver(7L, "courier");

        verify(backendClient).updateFulfilment(42L, FulfilmentState.DELIVERED);
        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipment.getDeliveredAt()).isEqualTo(NOW);
        ArgumentCaptor<CustomerMessage> message = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(notificationOutbox).enqueue(message.capture());
        assertThat(message.getValue().template()).isEqualTo(NotificationTemplate.ORDER_DELIVERED);
    }

    @Test
    void deliveringOneOfTwoShippedParcelsWaitsForTheOtherBeforeTheOrderIsDelivered() {
        shipment.setStatus(ShipmentStatus.SHIPPED);
        ShipmentEntity other = parcel(8L, "GDN", ShipmentStatus.SHIPPED);
        lock.setMirroredState(FulfilmentState.SHIPPED);

        service.deliver(7L, "courier");
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
        verify(notificationOutbox, never()).enqueue(any());

        service.deliver(8L, "courier");
        verify(backendClient).updateFulfilment(42L, FulfilmentState.DELIVERED);
        verify(notificationOutbox, times(1)).enqueue(any());
        assertThat(other.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void failedShipOrDeliverNeverNotifies() {
        shipment.setStatus(ShipmentStatus.PACKED);
        lock.setMirroredState(FulfilmentState.PACKED);
        when(backendClient.updateFulfilment(eq(42L), any())).thenThrow(UNAVAILABLE);

        assertThatThrownBy(() -> service.ship(7L, Carrier.DHL, "1234567890", "packer")).isSameAs(UNAVAILABLE);
        shipment.setStatus(ShipmentStatus.SHIPPED);
        lock.setMirroredState(FulfilmentState.SHIPPED);
        assertThatThrownBy(() -> service.deliver(7L, "courier")).isSameAs(UNAVAILABLE);

        verify(notificationOutbox, never()).enqueue(any());
    }

    @Test
    void packingRecordsTheWeight() {
        shipment.setStatus(ShipmentStatus.PICKED);
        lock.setMirroredState(FulfilmentState.PICKED);

        ShipmentDto packed = service.pack(7L, new BigDecimal("2.50"), "packer");

        verify(backendClient).updateFulfilment(42L, FulfilmentState.PACKED);
        assertThat(packed.getWeightKg()).isEqualByComparingTo("2.50");
        assertThat(shipment.getPackedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "PICKED", mode = EnumSource.Mode.EXCLUDE)
    void packRequiresAPickedParcel(ShipmentStatus status) {
        shipment.setStatus(status);
        assertThatThrownBy(() -> service.pack(7L, BigDecimal.ONE, "packer"))
                .hasMessage("Cannot pack a shipment in status " + status);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "PACKED", mode = EnumSource.Mode.EXCLUDE)
    void shipRequiresAPackedParcel(ShipmentStatus status) {
        shipment.setStatus(status);
        assertThatThrownBy(() -> service.ship(7L, Carrier.DHL, "1234567890", "packer"))
                .hasMessage("Cannot ship a shipment in status " + status);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "SHIPPED", mode = EnumSource.Mode.EXCLUDE)
    void deliverRequiresAShippedParcel(ShipmentStatus status) {
        shipment.setStatus(status);
        assertThatThrownBy(() -> service.deliver(7L, "courier"))
                .hasMessage("Cannot deliver a shipment in status " + status);
    }

    @Test
    void unknownParcelsAreNotFoundBeforeAnyLock() {
        assertThatThrownBy(() -> service.pick(999L, "picker"))
                .isInstanceOf(CustomException.class)
                .hasMessage("Shipment not found")
                .extracting("httpStatus").isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.get(999L)).hasMessage("Shipment not found");
        verify(orderLocks, never()).lockInTransaction(anyLong());
    }

    @Test
    void lockForUpdateReturnsTheParcelLockedAfterTheOrder() {
        assertThat(service.lockForUpdate(7L)).isSameAs(shipment);

        InOrder order = inOrder(orderLocks, shipmentRepository);
        order.verify(orderLocks).lockInTransaction(42L);
        order.verify(shipmentRepository).findByIdForUpdate(7L);
    }

    // --- cancellation ---

    @Test
    void cancellationCallsWarehouseCancelThenCancelsEveryUnpackedParcel() {
        shipment.setStatus(ShipmentStatus.PICKED);
        ShipmentEntity other = parcel(8L, "GDN", ShipmentStatus.AWAITING_PICK);

        ShipmentDto cancelled = service.cancel(7L, "operator");

        verify(backendClient).warehouseCancel(42L);
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(List.of(shipment, other)).allSatisfy(parcel -> {
            assertThat(parcel.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(parcel.getCancelledAt()).isEqualTo(NOW);
            assertThat(parcel.getLastActor()).isEqualTo("operator");
        });
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"AWAITING_PICK", "PICKED"}, mode = EnumSource.Mode.EXCLUDE)
    void cancellationIsOnlyAllowedBeforePacking(ShipmentStatus status) {
        shipment.setStatus(status);

        assertThatThrownBy(() -> service.cancel(7L, "operator"))
                .hasMessage("Cannot cancel a shipment in status " + status);
        verify(backendClient, never()).warehouseCancel(anyLong());
    }

    @Test
    void cancellationIsRefusedWhileAnotherParcelOfTheOrderIsPacked() {
        ShipmentEntity packed = parcel(8L, "GDN", ShipmentStatus.PACKED);
        parcel(9L, "WAW", ShipmentStatus.CANCELLED);

        assertThatThrownBy(() -> service.cancel(7L, "operator"))
                .isInstanceOf(CustomException.class)
                .hasMessage("Cannot cancel order 42: its parcel from GDN is already PACKED")
                .extracting("httpStatus").isEqualTo(HttpStatus.CONFLICT);

        verify(backendClient, never()).warehouseCancel(anyLong());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(packed.getStatus()).isEqualTo(ShipmentStatus.PACKED);
    }

    @Test
    void refusedWarehouseCancelKeepsTheParcelsUnlessTheOrderIsAlreadyCancelled() {
        when(backendClient.warehouseCancel(42L)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.PACKED));

        assertThatThrownBy(() -> service.cancel(7L, "operator")).isSameAs(CONFLICT);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void otherWarehouseCancelFailuresAreReportedWithoutReadingTheOrder() {
        when(backendClient.warehouseCancel(42L)).thenThrow(UNAVAILABLE);

        assertThatThrownBy(() -> service.cancel(7L, "operator")).isSameAs(UNAVAILABLE);

        verify(backendClient, never()).getOrder(anyLong());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void cancellingAnOrderTheBackendAlreadyCancelledSucceeds() {
        when(backendClient.warehouseCancel(42L)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.CANCELLED, FulfilmentState.CANCELLED));

        assertThat(service.cancel(7L, "operator").getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    // --- release ---

    @Test
    void releaseRequiresAPaidOrderWithoutFulfilment() {
        parcels.clear();
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PENDING, FulfilmentState.NONE));
        assertThatThrownBy(() -> service.create(42L, "operator"))
                .hasMessage("Only PAID orders can be released to the warehouse");

        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.PICKED));
        assertThatThrownBy(() -> service.create(42L, "operator")).hasMessage("Order already has a shipment");

        verify(allocation, never()).allocate(any());
        verify(shipmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void anOrderWithParcelsCannotBeReleasedAgain() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));

        assertThatThrownBy(() -> service.create(42L, "operator"))
                .isInstanceOf(CustomException.class)
                .hasMessage("Order already has a shipment")
                .extracting("httpStatus").isEqualTo(HttpStatus.CONFLICT);
        verify(orderLocks, never()).ensureExists(anyLong());
    }

    @Test
    void anUnstockedCategoryFailsTheReleaseBeforeAnythingIsWritten() {
        parcels.clear();
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "Kite", "Kites", 1));
        when(backendClient.getOrder(42L)).thenReturn(order);
        when(allocation.allocate(order)).thenThrow(
                new CustomException("No active warehouse location stocks Kites", HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.create(42L, "operator")).hasMessage("No active warehouse location stocks Kites");

        verify(orderLocks, never()).ensureExists(anyLong());
        verify(shipmentRepository, never()).saveAndFlush(any());
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
    }

    @Test
    void releaseWritesOneParcelPerLocationWithItsLinesThenMirrorsAwaitingPick() {
        parcels.clear();
        lock.setMirroredState(null);
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.NONE,
                item(1, "Phone", "Electronics", 2), item(2, "Novel", "Books", 1));
        when(backendClient.getOrder(42L)).thenReturn(order);
        when(allocation.allocate(order)).thenReturn(plan(
                "KRK", new ShipmentAllocator.Line(1L, "Phone", "Electronics", 2),
                "GDN", new ShipmentAllocator.Line(2L, "Novel", "Books", 1)));

        ShipmentDto first = service.create(42L, "operator");

        ArgumentCaptor<ShipmentEntity> saved = ArgumentCaptor.forClass(ShipmentEntity.class);
        InOrder order1 = inOrder(orderLocks, shipmentRepository, backendClient);
        order1.verify(orderLocks).ensureExists(42L);
        order1.verify(orderLocks).lockInTransaction(42L);
        order1.verify(shipmentRepository, times(2)).saveAndFlush(saved.capture());
        order1.verify(backendClient).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);
        assertThat(saved.getAllValues()).extracting(ShipmentEntity::getLocationCode).containsExactly("KRK", "GDN");
        assertThat(saved.getAllValues()).extracting(ShipmentEntity::getTotalUnits).containsExactly(2, 1);
        assertThat(saved.getAllValues()).allSatisfy(parcel -> {
            assertThat(parcel.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
            assertThat(parcel.getUsername()).isEqualTo("buyer");
            assertThat(parcel.getCreatedBy()).isEqualTo("operator");
            assertThat(parcel.getLastActor()).isEqualTo("operator");
        });
        ArgumentCaptor<ShipmentLineEntity> lines = ArgumentCaptor.forClass(ShipmentLineEntity.class);
        verify(lineRepository, times(2)).save(lines.capture());
        assertThat(lines.getAllValues()).extracting(l -> l.getShipmentId() + ":" + l.getProductId() + "x" + l.getQuantity()
                        + " " + l.getProductName() + "/" + l.getCategory())
                .containsExactly(saved.getAllValues().get(0).getId() + ":1x2 Phone/Electronics",
                        saved.getAllValues().get(1).getId() + ":2x1 Novel/Books");
        assertThat(first.getLocationCode()).isEqualTo("KRK");
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.AWAITING_PICK);
    }

    @Test
    void aConcurrentReleaseFoundUnderTheLockWinsAndNothingIsWritten() {
        parcels.clear();
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "Phone", "Electronics", 1));
        when(backendClient.getOrder(42L)).thenReturn(order);
        when(shipmentRepository.existsByOrderId(42L)).thenReturn(false, true);

        assertThatThrownBy(() -> service.create(42L, "operator")).hasMessage("Order already has a shipment");

        verify(orderLocks).lockInTransaction(42L);
        verify(shipmentRepository, never()).saveAndFlush(any());
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
    }

    @Test
    void aRefusedReleaseIsRethrownSoTheTransactionRemovesTheParcels() {
        parcels.clear();
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "Phone", "Electronics", 1));
        when(backendClient.getOrder(42L)).thenReturn(order);
        when(allocation.allocate(order)).thenReturn(plan("KRK", new ShipmentAllocator.Line(1L, "Phone", "Electronics", 1)));
        when(backendClient.updateFulfilment(42L, FulfilmentState.AWAITING_PICK)).thenThrow(CONFLICT);

        assertThatThrownBy(() -> service.create(42L, "operator")).isSameAs(CONFLICT);
        verify(backendClient, times(1)).getOrder(42L);
    }

    @Test
    void releaseKeepsTheParcelsWhenTheBackendAppliedItButTheResponseWasLost() {
        parcels.clear();
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "Phone", "Electronics", 1));
        when(backendClient.getOrder(42L)).thenReturn(order, order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));
        when(allocation.allocate(order)).thenReturn(plan("KRK", new ShipmentAllocator.Line(1L, "Phone", "Electronics", 1)));
        when(backendClient.updateFulfilment(42L, FulfilmentState.AWAITING_PICK)).thenThrow(UNAVAILABLE);

        assertThat(service.create(42L, "operator").getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void releaseFailsWhenTheBackendIsDownAndTheOrderWasNotReleased() {
        parcels.clear();
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "Phone", "Electronics", 1));
        when(backendClient.getOrder(42L)).thenReturn(order, order).thenThrow(UNAVAILABLE);
        when(allocation.allocate(order)).thenReturn(plan("KRK", new ShipmentAllocator.Line(1L, "Phone", "Electronics", 1)));
        when(backendClient.updateFulfilment(42L, FulfilmentState.AWAITING_PICK)).thenThrow(UNAVAILABLE);

        // The order is read again and still not released.
        assertThatThrownBy(() -> service.create(42L, "operator")).isSameAs(UNAVAILABLE);
        // The re-read itself fails: the release is not assumed to have reached the backend.
        org.mockito.Mockito.doReturn(order).doThrow(UNAVAILABLE).when(backendClient).getOrder(42L);
        assertThatThrownBy(() -> service.create(42L, "operator")).isSameAs(UNAVAILABLE);
        verify(backendClient, times(2)).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);
    }

    @Test
    void anOrderAlreadyAwaitingPickWithoutParcelsIsAdoptedOnRelease() {
        parcels.clear();
        InternalOrderDto order = order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK, item(1, "Phone", "Electronics", 1));
        when(backendClient.getOrder(42L)).thenReturn(order);
        when(allocation.allocate(order)).thenReturn(plan("KRK", new ShipmentAllocator.Line(1L, "Phone", "Electronics", 1)));

        assertThat(service.create(42L, "operator").getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        verify(backendClient).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);
    }

    // --- following backend cancellations ---

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"AWAITING_PICK", "PICKED"})
    void aCancelledOrderFromTheFeedCancelsUnpackedParcelsWithoutCallingTheBackend(ShipmentStatus status) {
        shipment.setStatus(status);
        ShipmentEntity packed = parcel(8L, "GDN", ShipmentStatus.PACKED);

        assertThat(service.followOrderCancellation(42L)).isTrue();

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.getLastActor()).isEqualTo("system");
        assertThat(packed.getStatus()).isEqualTo(ShipmentStatus.PACKED);
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.CANCELLED);
        verify(orderLocks).lockInTransaction(42L);
        verify(backendClient, never()).getOrder(anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"PACKED", "SHIPPED", "DELIVERED", "CANCELLED"})
    void aCancelledOrderFromTheFeedLeavesLaterParcelsAlone(ShipmentStatus status) {
        shipment.setStatus(status);

        assertThat(service.followOrderCancellation(42L)).isFalse();

        assertThat(shipment.getStatus()).isEqualTo(status);
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.AWAITING_PICK);
    }

    @Test
    void aCancelledOrderWithoutParcelsIsIgnoredWithoutALock() {
        parcels.clear();

        assertThat(service.followOrderCancellation(42L)).isFalse();

        verify(orderLocks, never()).lockInTransaction(anyLong());
    }

    @Test
    void reconciliationCancelsTheParcelsOfOrdersCancelledOnTheBackend() {
        shipment.setStatus(ShipmentStatus.AWAITING_PICK);
        ShipmentEntity picked = parcel(8L, "GDN", ShipmentStatus.PICKED);
        ShipmentEntity otherOrder = ShipmentEntity.builder().id(9L).orderId(43L).status(ShipmentStatus.AWAITING_PICK).build();
        when(shipmentRepository.findByStatus(eq(ShipmentStatus.AWAITING_PICK), any()))
                .thenReturn(new PageImpl<>(List.of(shipment, otherOrder)));
        InternalOrderDto active = new InternalOrderDto(43L, "x", OrderStatus.PAID, FulfilmentState.AWAITING_PICK,
                null, null, BigDecimal.ONE, null, null, List.of(), NOW, NOW);
        when(backendClient.getOrders(any())).thenReturn(
                List.of(order(OrderStatus.CANCELLED, FulfilmentState.CANCELLED), active));

        assertThat(service.reconcileCancellations()).isEqualTo(2);

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(picked.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(otherOrder.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        verify(orderLocks, never()).lockInTransaction(43L);
        verify(backendClient).getOrders(java.util.Set.of(42L, 43L));
    }

    @Test
    void reconciliationWithoutAwaitingParcelsDoesNotCallTheBackend() {
        when(shipmentRepository.findByStatus(eq(ShipmentStatus.AWAITING_PICK), any())).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.reconcileCancellations()).isZero();
        verify(backendClient, never()).getOrders(any());
    }

    // --- adoption ---

    @Test
    void releasedOrdersWithoutParcelsAreAdoptedOnceTheyAreOldEnough() {
        parcels.clear();
        lock.setMirroredState(null);
        InternalOrderDto old = order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK, NOW.minusMinutes(3),
                item(1, "Phone", "Electronics", 1));
        InternalOrderDto young = new InternalOrderDto(43L, "buyer", OrderStatus.PAID, FulfilmentState.AWAITING_PICK,
                null, null, BigDecimal.ONE, null, null, List.of(), NOW, NOW.minusMinutes(1));
        InternalOrderDto unknownAge = new InternalOrderDto(44L, "buyer", OrderStatus.PAID, FulfilmentState.AWAITING_PICK,
                null, null, BigDecimal.ONE, null, null, List.of(), NOW, null);
        PageDto<InternalOrderDto> page = new PageDto<>();
        page.setContent(List.of(old, young, unknownAge));
        when(backendClient.ordersAwaitingPick(0, 100)).thenReturn(page);
        when(allocation.allocate(old)).thenReturn(plan(
                "KRK", new ShipmentAllocator.Line(1L, "Phone", "Electronics", 1)));

        assertThat(service.adoptReleasedOrders()).isEqualTo(1);

        ArgumentCaptor<ShipmentEntity> saved = ArgumentCaptor.forClass(ShipmentEntity.class);
        verify(shipmentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("system");
        assertThat(saved.getValue().getOrderId()).isEqualTo(42L);
        assertThat(lock.getMirroredState()).isEqualTo(FulfilmentState.AWAITING_PICK);
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
        verify(orderLocks).ensureExists(42L);
        verify(orderLocks, never()).ensureExists(43L);
    }

    @Test
    void adoptionSkipsOrdersThatGotParcelsUnderTheLockOrCannotBeAllocated() {
        parcels.clear();
        InternalOrderDto old = order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK, NOW.minusMinutes(3),
                item(1, "Kite", "Kites", 1));
        PageDto<InternalOrderDto> page = new PageDto<>();
        page.setContent(List.of(old));
        when(backendClient.ordersAwaitingPick(0, 100)).thenReturn(page);
        when(shipmentRepository.existsByOrderId(42L)).thenReturn(false, true, false, false, false, false);
        when(allocation.allocate(old))
                .thenThrow(new CustomException("No active warehouse location stocks Kites", HttpStatus.CONFLICT))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.adoptReleasedOrders()).isZero();
        assertThat(service.adoptReleasedOrders()).isZero();
        assertThat(service.adoptReleasedOrders()).isZero();

        verify(shipmentRepository, never()).saveAndFlush(any());
    }

    // --- reads ---

    @Test
    void customersCanOnlyTrackTheirOwnOrdersAndSeeTheFirstParcel() {
        parcel(8L, "GDN", ShipmentStatus.AWAITING_PICK);

        assertThat(service.trackOrder(42L, "buyer", false).getId()).isEqualTo(7L);
        assertThat(service.orderShipments(42L, "someone", true)).extracting(ShipmentDto::getId).containsExactly(7L, 8L);
        assertThatThrownBy(() -> service.trackOrder(42L, "someone", false)).hasMessage("Order not found");
        assertThatThrownBy(() -> service.orderShipments(42L, "someone", false)).hasMessage("Order not found");
        verify(legacyLines, times(2)).fill(any());
        verify(backendClient, never()).getOrder(anyLong());
    }

    @Test
    void unreleasedOrdersAreCheckedAgainstTheBackend() {
        parcels.clear();
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.NONE));

        assertThat(service.orderShipments(42L, "buyer", false)).isEmpty();
        assertThatThrownBy(() -> service.trackOrder(42L, "buyer", false))
                .isInstanceOf(CustomException.class)
                .hasMessage("Shipment not found")
                .extracting("httpStatus").isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.trackOrder(42L, "someone", false)).hasMessage("Order not found");
        assertThat(service.orderShipments(42L, "admin", true)).isEmpty();
    }

    @Test
    void pickListUsesTheParcelLinesSortedByCategoryThenName() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));
        when(lineRepository.findByShipmentIdOrderByIdAsc(7L)).thenReturn(List.of(
                ShipmentLineEntity.builder().productId(1L).productName("Zoom").category("Audio").quantity(1).build(),
                ShipmentLineEntity.builder().productId(2L).productName("apple").category(null).quantity(2).build(),
                ShipmentLineEntity.builder().productId(3L).productName("Bass").category("audio").quantity(3).build(),
                ShipmentLineEntity.builder().productId(4L).productName("Aardvark").category("Zoo").quantity(1).build()));

        PickListDto pickList = service.pickList(7L);

        assertThat(pickList.getItems()).extracting(i -> i.getProductName())
                .containsExactly("apple", "Bass", "Zoom", "Aardvark");
        assertThat(pickList.getTotalUnits()).isEqualTo(7);
        assertThat(pickList.getShipmentId()).isEqualTo(7L);
        assertThat(pickList.getOrderId()).isEqualTo(42L);
        assertThat(pickList.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(pickList.getShippingAddress().getCity()).isEqualTo("c");
    }

    @Test
    void aPreMigrationParcelPicksTheWholeOrderMergedByProduct() {
        shipment.setLinesPending(true);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK,
                item(1, "Phone", "Electronics", 1), item(2, "Cable", "Accessories", 2), item(1, "Phone", "Electronics", 2)));

        PickListDto pickList = service.pickList(7L);

        assertThat(pickList.getItems()).extracting(i -> i.getProductName() + "x" + i.getQuantity())
                .containsExactly("Cablex2", "Phonex3");
        verify(lineRepository, never()).findByShipmentIdOrderByIdAsc(anyLong());
    }

    @Test
    void readingAParcelFillsLegacyLinesButNeverChangesItsState() {
        ShipmentDto read = service.get(7L);

        assertThat(read.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        verify(legacyLines).fill(List.of(shipment));
        verify(orderLocks, never()).lockInTransaction(anyLong());
        verify(shipmentRepository, never()).saveAndFlush(any());
        verify(backendClient, never()).getOrder(anyLong());
    }

    @Test
    void listingFillsLegacyLinesAndKeepsThePageOrder() {
        ShipmentEntity other = parcel(8L, "GDN", ShipmentStatus.PICKED);
        when(shipmentRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(other, shipment)));
        when(shipmentRepository.findByStatus(eq(ShipmentStatus.PICKED), any())).thenReturn(new PageImpl<>(List.of(other)));

        assertThat(service.list(null, PageRequest.of(0, 20)).getContent()).extracting(ShipmentDto::getId)
                .containsExactly(8L, 7L);
        assertThat(service.list(ShipmentStatus.PICKED, PageRequest.of(0, 20)).getContent()).extracting(ShipmentDto::getId)
                .containsExactly(8L);
        verify(legacyLines).fill(List.of(other, shipment));
    }

    @Test
    void theQueueComesFromTheBackend() {
        PageDto<InternalOrderDto> page = new PageDto<>();
        page.setContent(List.of(order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "Phone", "Electronics", 1))));
        when(backendClient.fulfilmentQueue(0, 20)).thenReturn(page);

        assertThat(service.queue(0, 20).getContent()).singleElement()
                .satisfies(o -> assertThat(o.getId()).isEqualTo(42L));
    }

    @Test
    void aFailedMirrorStepAfterAnEarlierOneLeavesTheLocalStateForTheRetry() {
        lock.setMirroredState(null);
        when(backendClient.updateFulfilment(eq(42L), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(1) == FulfilmentState.PICKED) {
                throw UNAVAILABLE;
            }
            return null;
        });

        assertThatThrownBy(() -> service.pick(7L, "picker")).isSameAs(UNAVAILABLE);

        verify(backendClient).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);
        assertThat(lock.getMirroredState()).isNull();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }
}
