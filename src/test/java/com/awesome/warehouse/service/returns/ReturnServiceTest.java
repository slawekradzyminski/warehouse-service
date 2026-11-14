package com.awesome.warehouse.service.returns;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.OrderRefundRequest;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.client.ReceiptType;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.returns.CreateReturnDto;
import com.awesome.warehouse.dto.returns.ReceiveItemDto;
import com.awesome.warehouse.dto.returns.ReturnDto;
import com.awesome.warehouse.dto.returns.ReturnItemRequestDto;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.returns.ItemCondition;
import com.awesome.warehouse.entity.returns.ReturnItemEntity;
import com.awesome.warehouse.entity.returns.ReturnReason;
import com.awesome.warehouse.entity.returns.ReturnRequestEntity;
import com.awesome.warehouse.entity.returns.ReturnStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.returns.ReturnRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import com.awesome.warehouse.service.notification.CustomerMessages.CustomerMessage;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 12, 0);

    @Mock private ReturnRequestRepository returnRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private BackendClient backendClient;
    @Mock private NotificationOutbox notificationOutbox;
    @Mock private OrderLocks orderLocks;

    private ReturnService service;

    @BeforeEach
    void setUp() {
        service = new ReturnService(returnRepository, shipmentRepository, backendClient, orderLocks, notificationOutbox,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        lenient().when(returnRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(orderLocks.callLocked(anyLong(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
    }

    private static InternalOrderDto order(OrderStatus status, LocalDateTime updatedAt, String total, String subtotal,
                                          InternalOrderItemDto... items) {
        return new InternalOrderDto(42L, "buyer", status, FulfilmentState.DELIVERED,
                subtotal == null ? null : new BigDecimal(subtotal), BigDecimal.ZERO, new BigDecimal(total), null, null,
                List.of(items), NOW.minusDays(40), updatedAt);
    }

    private static InternalOrderItemDto item(long productId, int quantity, String price) {
        return new InternalOrderItemDto(null, productId, "Product " + productId, "Cat", quantity, new BigDecimal(price));
    }

    private static CreateReturnDto request(ReturnItemRequestDto... items) {
        return new CreateReturnDto(ReturnReason.DAMAGED, "  broken  ", List.of(items));
    }

    private static ReturnItemRequestDto line(long productId, int quantity) {
        return new ReturnItemRequestDto(productId, quantity);
    }

    private void givenOrder(InternalOrderDto order) {
        when(backendClient.getOrder(42L)).thenReturn(order);
    }

    @Test
    void createSnapshotsOrderTotalsNamesAndPrices() {
        givenOrder(order(OrderStatus.DELIVERED, NOW, "126.00", "140.00", item(1, 1, "100.00"), item(2, 2, "20.00")));

        ReturnDto dto = service.create("buyer", 42L, request(line(1, 1), line(2, 1)));

        ArgumentCaptor<ReturnRequestEntity> saved = ArgumentCaptor.forClass(ReturnRequestEntity.class);
        verify(returnRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOrderTotalAmount()).isEqualByComparingTo("126.00");
        assertThat(saved.getValue().getOrderSubtotalAmount()).isEqualByComparingTo("140.00");
        assertThat(saved.getValue().getComment()).isEqualTo("broken");
        assertThat(saved.getValue().getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(saved.getValue().getItems()).extracting(ReturnItemEntity::getProductName)
                .containsExactly("Product 1", "Product 2");
        assertThat(dto.getEstimatedRefund()).isEqualByComparingTo("108.00");
        assertThat(dto.getItems()).extracting(i -> i.getUnitPrice()).containsExactly(
                new BigDecimal("100.00"), new BigDecimal("20.00"));
    }

    @Test
    void ordersOfOtherCustomersAreNotFound() {
        givenOrder(order(OrderStatus.DELIVERED, NOW, "10.00", "10.00", item(1, 1, "10.00")));

        assertThatThrownBy(() -> service.create("stranger", 42L, request(line(1, 1))))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Order not found");
                });
        verify(orderLocks, never()).callLocked(anyLong(), any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID", "SHIPPED", "CANCELLED"})
    void onlyDeliveredOrdersCanBeReturned(OrderStatus status) {
        givenOrder(order(status, NOW, "10.00", "10.00", item(1, 1, "10.00")));

        assertThatThrownBy(() -> service.create("buyer", 42L, request(line(1, 1))))
                .hasMessage("Only delivered orders can be returned");
    }

    @Test
    void windowIsThirtyDaysFromShipmentDeliveryInclusive() {
        givenOrder(order(OrderStatus.DELIVERED, NOW, "10.00", "10.00", item(1, 2, "10.00")));
        // Two parcels: the window starts with the later delivery.
        ShipmentEntity first = ShipmentEntity.builder().deliveredAt(NOW.minusDays(40)).build();
        ShipmentEntity last = ShipmentEntity.builder().deliveredAt(NOW.minusDays(30)).build();
        ShipmentEntity undelivered = ShipmentEntity.builder().build();
        when(shipmentRepository.findByOrderIdOrderByIdAsc(42L)).thenReturn(List.of(first, undelivered, last));

        assertThat(service.create("buyer", 42L, request(line(1, 1)))).isNotNull();

        last.setDeliveredAt(NOW.minusDays(30).minusSeconds(1));
        assertThatThrownBy(() -> service.create("buyer", 42L, request(line(1, 1))))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("The 30-day return window has passed");
                });
    }

    @Test
    void withoutShipmentTheOrderUpdateTimeStartsTheWindow() {
        givenOrder(order(OrderStatus.DELIVERED, NOW.minusDays(31), "10.00", "10.00", item(1, 1, "10.00")));

        assertThatThrownBy(() -> service.create("buyer", 42L, request(line(1, 1))))
                .hasMessage("The 30-day return window has passed");
    }

    @Test
    void returnableQuantityIsOrderedMinusOtherNonRejectedReturns() {
        givenOrder(order(OrderStatus.DELIVERED, NOW, "50.00", "50.00", item(1, 2, "10.00"), item(1, 3, "10.00")));
        ReturnRequestEntity previous = ReturnRequestEntity.builder().items(new ArrayList<>()).build();
        previous.addItem(ReturnItemEntity.builder().productId(1L).quantity(4).build());
        when(returnRepository.findByOrderIdAndStatusNot(42L, ReturnStatus.REJECTED)).thenReturn(List.of(previous));

        assertThat(service.create("buyer", 42L, request(line(1, 1))).getItems()).hasSize(1);
        assertThatThrownBy(() -> service.create("buyer", 42L, request(line(1, 2))))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage())
                            .isEqualTo("Requested quantity for product 1 exceeds the returnable quantity of 1");
                });
    }

    @Test
    void duplicateAndForeignProductsAreBadRequests() {
        givenOrder(order(OrderStatus.DELIVERED, NOW, "10.00", "10.00", item(1, 1, "10.00")));

        assertThatThrownBy(() -> service.create("buyer", 42L, request(line(1, 1), line(1, 1))))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Product 1 is listed more than once");
                });
        assertThatThrownBy(() -> service.create("buyer", 42L, request(line(9, 1))))
                .hasMessage("Product 9 is not part of this order");
        verify(returnRepository, never()).saveAndFlush(any());
    }

    private ReturnRequestEntity existing(ReturnStatus status) {
        ReturnRequestEntity request = ReturnRequestEntity.builder().id(5L).orderId(42L).username("buyer")
                .status(status).orderTotalAmount(new BigDecimal("126.00")).orderSubtotalAmount(new BigDecimal("140.00"))
                .items(new ArrayList<>()).build();
        request.addItem(ReturnItemEntity.builder().productId(2L).productName("Cable").quantity(1)
                .unitPrice(new BigDecimal("20.00")).build());
        request.addItem(ReturnItemEntity.builder().productId(1L).productName("Phone").quantity(1)
                .unitPrice(new BigDecimal("100.00")).build());
        when(returnRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(request));
        return request;
    }

    @Test
    void receiveRestocksOnlyResellableLinesWithADeterministicRequestId() {
        ReturnRequestEntity request = existing(ReturnStatus.APPROVED);

        ReturnDto dto = service.receive(5L, List.of(new ReceiveItemDto(1L, ItemCondition.RESELLABLE),
                new ReceiveItemDto(2L, ItemCondition.DAMAGED)), "admin");

        assertThat(dto.getStatus()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(dto.getItems()).extracting(i -> i.getCondition())
                .containsExactly(ItemCondition.DAMAGED, ItemCondition.RESELLABLE);
        ArgumentCaptor<InventoryReceiptRequest> receipt = ArgumentCaptor.forClass(InventoryReceiptRequest.class);
        verify(backendClient).receiveInventory(receipt.capture());
        assertThat(receipt.getValue().requestId())
                .isEqualTo(UUID.nameUUIDFromBytes("return-5".getBytes(StandardCharsets.UTF_8)));
        assertThat(receipt.getValue().type()).isEqualTo(ReceiptType.RETURN_RESTOCKED);
        assertThat(receipt.getValue().orderId()).isEqualTo(42L);
        assertThat(receipt.getValue().reference()).isEqualTo("Return #5");
        assertThat(receipt.getValue().lines()).containsExactly(new ReceiptLine(1L, 1));
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(request.getReceivedAt()).isEqualTo(NOW);
        assertThat(request.getItems()).extracting(ReturnItemEntity::getCondition)
                .containsExactly(ItemCondition.DAMAGED, ItemCondition.RESELLABLE);
        verify(notificationOutbox, never()).enqueue(any());
    }

    @Test
    void receiveSortsRestockLinesByProduct() {
        existing(ReturnStatus.APPROVED);

        service.receive(5L, List.of(new ReceiveItemDto(2L, ItemCondition.RESELLABLE),
                new ReceiveItemDto(1L, ItemCondition.RESELLABLE)), "admin");

        ArgumentCaptor<InventoryReceiptRequest> receipt = ArgumentCaptor.forClass(InventoryReceiptRequest.class);
        verify(backendClient).receiveInventory(receipt.capture());
        assertThat(receipt.getValue().lines()).containsExactly(new ReceiptLine(1L, 1), new ReceiptLine(2L, 1));
    }

    @Test
    void receiveWithoutResellableItemsDoesNotCallTheBackend() {
        existing(ReturnStatus.APPROVED);

        service.receive(5L, List.of(new ReceiveItemDto(1L, ItemCondition.DAMAGED),
                new ReceiveItemDto(2L, ItemCondition.DAMAGED)), "admin");

        verify(backendClient, never()).receiveInventory(any());
    }

    @Test
    void failedRestockLeavesTheReturnApproved() {
        ReturnRequestEntity request = existing(ReturnStatus.APPROVED);
        when(backendClient.receiveInventory(any()))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.receive(5L, List.of(new ReceiveItemDto(1L, ItemCondition.RESELLABLE),
                new ReceiveItemDto(2L, ItemCondition.DAMAGED)), "admin")).hasMessage("Backend unavailable");
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(request.getItems()).extracting(ReturnItemEntity::getCondition).containsOnlyNulls();
        verify(returnRepository, never()).saveAndFlush(any());
    }

    @Test
    void receiveNeedsExactlyOneConditionPerReturnedProduct() {
        existing(ReturnStatus.APPROVED);

        assertThatThrownBy(() -> service.receive(5L, List.of(new ReceiveItemDto(1L, ItemCondition.RESELLABLE)), "a"))
                .hasMessage("Conditions must cover exactly the returned products [1, 2]");
        assertThatThrownBy(() -> service.receive(5L, List.of(new ReceiveItemDto(1L, ItemCondition.RESELLABLE),
                new ReceiveItemDto(1L, ItemCondition.DAMAGED)), "a"))
                .hasMessage("Product 1 is listed more than once");
        verify(backendClient, never()).receiveInventory(any());
    }

    @Test
    void refundPaysTheProportionalAmountAndNotifies() {
        ReturnRequestEntity request = existing(ReturnStatus.RECEIVED);

        ReturnDto dto = service.refund(5L);

        assertThat(request.getRefundAmount()).isEqualByComparingTo("108.00");
        assertThat(dto.getRefundAmount()).isEqualByComparingTo("108.00");
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(request.getRefundedAt()).isEqualTo(NOW);
        ArgumentCaptor<CustomerMessage> notification = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(notificationOutbox).enqueue(notification.capture());
        assertThat(notification.getValue().properties()).containsEntry("status", "REFUNDED")
                .containsEntry("refundAmount", "108.00").containsEntry("orderId", "42");
        ArgumentCaptor<OrderRefundRequest> refund = ArgumentCaptor.forClass(OrderRefundRequest.class);
        verify(backendClient).refundOrder(eq(42L), refund.capture());
        assertThat(refund.getValue().amount()).isEqualByComparingTo("108.00");
        assertThat(refund.getValue().requestId()).isEqualTo(ReturnService.refundRequestId(5L));
        assertThat(refund.getValue().reference()).isEqualTo("Return #5");
    }

    @Test
    void aRejectedRefundLeavesTheReturnReceived() {
        ReturnRequestEntity request = existing(ReturnStatus.RECEIVED);
        when(backendClient.refundOrder(eq(42L), any()))
                .thenThrow(new CustomException("Refund exceeds the refundable amount", HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.refund(5L)).isInstanceOf(CustomException.class);
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(request.getRefundAmount()).isNull();
        verify(notificationOutbox, never()).enqueue(any());
    }

    @Test
    void approveAndRejectNotifyTheCustomer() {
        ReturnRequestEntity request = existing(ReturnStatus.REQUESTED);
        ReturnDto dto = service.reject(5L, "  Used  ");

        assertThat(dto.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(dto.getResolutionNote()).isEqualTo("Used");

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(request.getResolutionNote()).isEqualTo("Used");
        assertThat(request.getRejectedAt()).isEqualTo(NOW);
        ArgumentCaptor<CustomerMessage> notification = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(notificationOutbox).enqueue(notification.capture());
        assertThat(notification.getValue().properties()).containsEntry("note", "Used");
        assertThat(notification.getValue().properties()).containsEntry("status", "REJECTED").containsEntry("returnId", "5");
    }

    @Test
    void approveMovesRequestedToApproved() {
        ReturnRequestEntity request = existing(ReturnStatus.REQUESTED);

        ReturnDto dto = service.approve(5L);

        assertThat(dto.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(dto.getApprovedAt()).isEqualTo(NOW);
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(request.getApprovedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @EnumSource(value = ReturnStatus.class, names = {"APPROVED", "REJECTED", "RECEIVED", "REFUNDED"})
    void approveAndRejectRequireRequested(ReturnStatus status) {
        existing(status);
        assertThatThrownBy(() -> service.approve(5L)).hasMessage("Cannot approve a return in status " + status);
        assertThatThrownBy(() -> service.reject(5L, "x")).hasMessage("Cannot reject a return in status " + status);
    }

    @ParameterizedTest
    @EnumSource(value = ReturnStatus.class, names = {"REQUESTED", "REJECTED", "RECEIVED", "REFUNDED"})
    void receiveRequiresApproved(ReturnStatus status) {
        existing(status);
        assertThatThrownBy(() -> service.receive(5L, List.of(), "a")).hasMessage("Cannot receive a return in status " + status);
    }

    @ParameterizedTest
    @EnumSource(value = ReturnStatus.class, names = {"REQUESTED", "APPROVED", "REJECTED", "REFUNDED"})
    void refundRequiresReceived(ReturnStatus status) {
        existing(status);
        assertThatThrownBy(() -> service.refund(5L)).hasMessage("Cannot refund a return in status " + status);
    }

    @Test
    void unknownReturnsAreNotFound() {
        assertThatThrownBy(() -> service.approve(6L)).hasMessage("Return not found");
        assertThatThrownBy(() -> service.get(6L)).hasMessage("Return not found");
        assertThatThrownBy(() -> service.getMine("buyer", 6L)).hasMessage("Return not found");
    }
}
