package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalAddressDto;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 12, 0);
    private static final CustomException CONFLICT = new CustomException("Fulfilment transition not allowed", HttpStatus.CONFLICT);

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private BackendClient backendClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ShipmentService service;
    private ShipmentEntity shipment;

    @BeforeEach
    void setUp() {
        service = new ShipmentService(shipmentRepository, backendClient, eventPublisher,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        shipment = ShipmentEntity.builder().id(7L).orderId(42L).username("buyer").totalUnits(5)
                .status(ShipmentStatus.AWAITING_PICK).build();
        lenient().when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
        lenient().when(shipmentRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(shipment));
        lenient().when(shipmentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static InternalOrderDto order(OrderStatus status, FulfilmentState state, InternalOrderItemDto... items) {
        return new InternalOrderDto(42L, "buyer", status, state, new BigDecimal("10.00"), BigDecimal.ZERO,
                new BigDecimal("10.00"), null, new InternalAddressDto("s", "c", "st", "00-000", "PL"), List.of(items),
                NOW, NOW);
    }

    private static InternalOrderItemDto item(long productId, String name, String category, int quantity) {
        return new InternalOrderItemDto(null, productId, name, category, quantity, BigDecimal.ONE);
    }

    @Test
    void pickValidatesLocallyMirrorsToTheBackendThenPersists() {
        service.pick(7L, "picker");

        InOrder order = inOrder(shipmentRepository, backendClient);
        order.verify(shipmentRepository).findByIdForUpdate(7L);
        order.verify(backendClient).updateFulfilment(42L, FulfilmentState.PICKED);
        order.verify(shipmentRepository).saveAndFlush(shipment);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PICKED);
        assertThat(shipment.getPickedAt()).isEqualTo(NOW);
        assertThat(shipment.getLastActor()).isEqualTo("picker");
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"PICKED", "PACKED", "SHIPPED", "DELIVERED", "CANCELLED"})
    void pickIsOnlyAllowedWhileAwaitingPickAndNeverReachesTheBackend(ShipmentStatus status) {
        shipment.setStatus(status);

        assertThatThrownBy(() -> service.pick(7L, "picker"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Cannot pick a shipment in status " + status);
                });
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
    }

    @Test
    void backendFailureLeavesTheShipmentUntouched() {
        when(backendClient.updateFulfilment(42L, FulfilmentState.PICKED))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.pick(7L, "picker")).hasMessage("Backend unavailable");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(shipment.getPickedAt()).isNull();
        verify(shipmentRepository, never()).saveAndFlush(any());
        verify(backendClient, never()).getOrder(anyLong());
    }

    @Test
    void conflictCausedByACustomerCancellationCancelsTheLocalShipment() {
        when(backendClient.updateFulfilment(42L, FulfilmentState.PICKED)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.CANCELLED, FulfilmentState.CANCELLED));

        assertThatThrownBy(() -> service.pick(7L, "picker"))
                .isInstanceOf(ShipmentCancelledException.class)
                .hasMessage("Order was cancelled by the customer");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.getCancelledAt()).isEqualTo(NOW);
        assertThat(shipment.getLastActor()).isEqualTo("system");
        verify(shipmentRepository).saveAndFlush(shipment);
    }

    @Test
    void otherConflictsAreReportedWithoutChangingTheShipment() {
        when(backendClient.updateFulfilment(42L, FulfilmentState.PICKED)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.PICKED));

        assertThatThrownBy(() -> service.pick(7L, "picker")).isSameAs(CONFLICT);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        verify(shipmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void shippingNormalisesTheTrackingNumberMirrorsAndNotifies() {
        shipment.setStatus(ShipmentStatus.PACKED);

        service.ship(7L, Carrier.UPS, " 1z999aa10123456784 ", "packer");

        assertThat(shipment.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(shipment.getCarrier()).isEqualTo(Carrier.UPS);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(shipment.getShippedAt()).isEqualTo(NOW);
        verify(backendClient).updateFulfilment(42L, FulfilmentState.SHIPPED);
        ArgumentCaptor<NotificationRequest> notification = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(eventPublisher).publishEvent(notification.capture());
        assertThat(notification.getValue().template()).isEqualTo(NotificationTemplate.ORDER_SHIPPED);
        assertThat(notification.getValue().username()).isEqualTo("buyer");
        assertThat(notification.getValue().message())
                .isEqualTo("Hi buyer,\n\nYour order #42 is on its way with UPS. Tracking number: 1Z999AA10123456784.");
    }

    @Test
    void invalidTrackingNumbersAreRejectedBeforeAnyLockOrBackendCall() {
        shipment.setStatus(ShipmentStatus.PACKED);

        assertThatThrownBy(() -> service.ship(7L, Carrier.DHL, "123", "packer"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Tracking number for DHL must be 10 digits");
                });
        verify(shipmentRepository, never()).findByIdForUpdate(any());
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PACKED);
    }

    @Test
    void deliveryMirrorsDeliveredAndNotifies() {
        shipment.setStatus(ShipmentStatus.SHIPPED);

        service.deliver(7L, "driver");

        assertThat(shipment.getDeliveredAt()).isEqualTo(NOW);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        verify(backendClient).updateFulfilment(42L, FulfilmentState.DELIVERED);
        ArgumentCaptor<NotificationRequest> notification = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(eventPublisher).publishEvent(notification.capture());
        assertThat(notification.getValue().template()).isEqualTo(NotificationTemplate.ORDER_DELIVERED);
        assertThat(notification.getValue().subject()).isEqualTo("Order #42 was delivered");
    }

    @Test
    void failedShipOrDeliverNeverNotifies() {
        shipment.setStatus(ShipmentStatus.PACKED);
        when(backendClient.updateFulfilment(42L, FulfilmentState.SHIPPED))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.ship(7L, Carrier.DHL, "1234567890", "packer"));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PACKED);
    }

    @Test
    void packingRecordsTheWeight() {
        shipment.setStatus(ShipmentStatus.PICKED);

        service.pack(7L, new BigDecimal("2.40"), "packer");

        assertThat(shipment.getWeightKg()).isEqualByComparingTo("2.40");
        assertThat(shipment.getPackedAt()).isEqualTo(NOW);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PACKED);
        verify(backendClient).updateFulfilment(42L, FulfilmentState.PACKED);
    }

    @Test
    void cancellationCallsWarehouseCancelThenMarksTheShipment() {
        shipment.setStatus(ShipmentStatus.PICKED);

        assertThat(service.cancel(7L, "lead")).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(dto.getLastActor()).isEqualTo("lead");
            assertThat(dto.getCancelledAt()).isEqualTo(NOW);
        });
        InOrder order = inOrder(backendClient, shipmentRepository);
        order.verify(backendClient).warehouseCancel(42L);
        order.verify(shipmentRepository).saveAndFlush(shipment);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"PACKED", "SHIPPED", "DELIVERED", "CANCELLED"})
    void cancellationIsOnlyAllowedBeforePacking(ShipmentStatus status) {
        shipment.setStatus(status);

        assertThatThrownBy(() -> service.cancel(7L, "lead")).hasMessage("Cannot cancel a shipment in status " + status);
        verify(backendClient, never()).warehouseCancel(anyLong());
    }

    @Test
    void refusedWarehouseCancelKeepsTheShipmentUnlessTheOrderIsAlreadyCancelled() {
        when(backendClient.warehouseCancel(42L)).thenThrow(CONFLICT);
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));

        assertThatThrownBy(() -> service.cancel(7L, "lead")).isSameAs(CONFLICT);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void releaseRequiresAPaidOrderWithoutFulfilment() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PENDING, FulfilmentState.NONE));
        assertThatThrownBy(() -> service.create(42L, "lead")).hasMessage("Only PAID orders can be released to the warehouse");

        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));
        assertThatThrownBy(() -> service.create(42L, "lead")).hasMessage("Order already has a shipment");

        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.NONE));
        when(shipmentRepository.existsByOrderId(42L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(42L, "lead")).hasMessage("Order already has a shipment");
        verify(shipmentRepository, never()).saveAndFlush(any());
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
    }

    @Test
    void releaseSnapshotsTheOrderSavesThenMirrorsAwaitingPick() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.NONE,
                item(1, "A", "X", 2), item(2, "B", "Y", 3)));

        assertThat(service.create(42L, "lead")).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
            assertThat(dto.getUsername()).isEqualTo("buyer");
            assertThat(dto.getTotalUnits()).isEqualTo(5);
            assertThat(dto.getCreatedBy()).isEqualTo("lead");
            assertThat(dto.getLastActor()).isEqualTo("lead");
        });
        InOrder order = inOrder(shipmentRepository, backendClient);
        order.verify(shipmentRepository).saveAndFlush(any());
        order.verify(backendClient).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);
    }

    @Test
    void releaseRemovesTheLocalShipmentWhenTheBackendRefuses() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.NONE, item(1, "A", "X", 1)));
        doThrow(CONFLICT).when(backendClient).updateFulfilment(42L, FulfilmentState.AWAITING_PICK);

        assertThatThrownBy(() -> service.create(42L, "lead")).isSameAs(CONFLICT);
        ArgumentCaptor<ShipmentEntity> deleted = ArgumentCaptor.forClass(ShipmentEntity.class);
        verify(shipmentRepository).delete(deleted.capture());
        assertThat(deleted.getValue().getOrderId()).isEqualTo(42L);
    }

    @Test
    void customersCanOnlyTrackTheirOwnOrders() {
        when(shipmentRepository.findByOrderId(42L)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.trackOrder(42L, "stranger", false)).hasMessage("Order not found");
        assertThat(service.trackOrder(42L, "buyer", false).getId()).isEqualTo(7L);
        assertThat(service.trackOrder(42L, "stranger", true).getId()).isEqualTo(7L);
        verify(backendClient, never()).getOrder(anyLong());
    }

    @Test
    void unreleasedOrdersAreCheckedAgainstTheBackend() {
        when(shipmentRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.NONE));

        assertThatThrownBy(() -> service.trackOrder(42L, "stranger", false)).hasMessage("Order not found");
        assertThatThrownBy(() -> service.trackOrder(42L, "buyer", false)).hasMessage("Shipment not found");
        assertThatThrownBy(() -> service.trackOrder(42L, "stranger", true)).hasMessage("Shipment not found");
    }

    @Test
    void pickListGroupsByProductAndSortsByCategoryThenName() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK,
                item(3, "zebra", "b-aisle", 1), item(1, "Apple", "B-aisle", 1), item(2, "Mango", "a-aisle", 2),
                item(3, "zebra", "b-aisle", 4)));

        PickListDto pickList = service.pickList(7L);

        assertThat(pickList.getItems()).extracting(i -> i.getProductId()).containsExactly(2L, 1L, 3L);
        assertThat(pickList.getItems().getLast().getQuantity()).isEqualTo(5);
        assertThat(pickList.getTotalUnits()).isEqualTo(8);
        assertThat(pickList.getOrderId()).isEqualTo(42L);
        assertThat(pickList.getShipmentId()).isEqualTo(7L);
        assertThat(pickList.getShippingAddress().getZipCode()).isEqualTo("00-000");
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"AWAITING_PICK", "PACKED", "SHIPPED", "DELIVERED", "CANCELLED"})
    void packRequiresAPickedShipment(ShipmentStatus status) {
        shipment.setStatus(status);
        assertThatThrownBy(() -> service.pack(7L, BigDecimal.ONE, "packer")).hasMessage("Cannot pack a shipment in status " + status);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"AWAITING_PICK", "PICKED", "SHIPPED", "DELIVERED", "CANCELLED"})
    void shipRequiresAPackedShipment(ShipmentStatus status) {
        shipment.setStatus(status);
        assertThatThrownBy(() -> service.ship(7L, Carrier.DHL, "1234567890", "packer")).hasMessage("Cannot ship a shipment in status " + status);
        verify(backendClient, never()).updateFulfilment(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"AWAITING_PICK", "PICKED", "PACKED", "DELIVERED", "CANCELLED"})
    void deliverRequiresAShippedShipment(ShipmentStatus status) {
        shipment.setStatus(status);
        assertThatThrownBy(() -> service.deliver(7L, "driver")).hasMessage("Cannot deliver a shipment in status " + status);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void transitionsReturnTheUpdatedShipmentView() {
        assertThat(service.pick(7L, "picker")).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.PICKED);
            assertThat(dto.getTotalUnits()).isEqualTo(5);
            assertThat(dto.getUsername()).isEqualTo("buyer");
            assertThat(dto.getLastActor()).isEqualTo("picker");
            assertThat(dto.getPickedAt()).isEqualTo(NOW);
        });
        assertThat(service.pack(7L, BigDecimal.TEN, "packer")).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.PACKED);
            assertThat(dto.getWeightKg()).isEqualByComparingTo("10");
        });
        assertThat(service.ship(7L, Carrier.DPD, "12345678901234", "packer")).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(dto.getCarrier()).isEqualTo(Carrier.DPD);
            assertThat(dto.getTrackingNumber()).isEqualTo("12345678901234");
            assertThat(dto.getShippedAt()).isEqualTo(NOW);
        });
        assertThat(service.deliver(7L, "driver")).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(dto.getDeliveredAt()).isEqualTo(NOW);
            assertThat(dto.getLastActor()).isEqualTo("driver");
        });
    }

    @Test
    void readingAnAwaitingPickShipmentPicksUpACustomerCancellation() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.CANCELLED, FulfilmentState.CANCELLED));

        assertThat(service.get(7L)).satisfies(dto -> {
            assertThat(dto.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(dto.getLastActor()).isEqualTo("system");
            assertThat(dto.getCancelledAt()).isEqualTo(NOW);
        });
        verify(shipmentRepository).saveAndFlush(shipment);
    }

    @Test
    void readingKeepsTheLocalStateWhenTheOrderIsActiveOrTheBackendIsDown() {
        when(backendClient.getOrder(42L)).thenReturn(order(OrderStatus.PAID, FulfilmentState.AWAITING_PICK));
        assertThat(service.get(7L).getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);

        when(backendClient.getOrder(42L)).thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(service.get(7L).getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        verify(shipmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void readingAShipmentPastAwaitingPickDoesNotCallTheBackend() {
        shipment.setStatus(ShipmentStatus.PICKED);

        assertThat(service.get(7L).getStatus()).isEqualTo(ShipmentStatus.PICKED);
        verify(backendClient, never()).getOrder(anyLong());
    }

    @Test
    void unknownShipmentsAreNotFound() {
        assertThatThrownBy(() -> service.pick(8L, "picker")).hasMessage("Shipment not found");
        assertThatThrownBy(() -> service.get(8L)).hasMessage("Shipment not found");
    }
}
