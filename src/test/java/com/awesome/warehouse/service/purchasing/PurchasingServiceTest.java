package com.awesome.warehouse.service.purchasing;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.client.ReceiptType;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderLineRequestDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderRequestDto;
import com.awesome.warehouse.dto.purchasing.ReceiptLineDto;
import com.awesome.warehouse.dto.purchasing.ReceiptRequestDto;
import com.awesome.warehouse.dto.purchasing.ReorderSuggestionDto;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderEntity;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderLineEntity;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderStatus;
import com.awesome.warehouse.entity.purchasing.PurchaseReceiptEntity;
import com.awesome.warehouse.entity.purchasing.SupplierEntity;
import com.awesome.warehouse.repository.purchasing.PurchaseOrderRepository;
import com.awesome.warehouse.repository.purchasing.PurchaseReceiptRepository;
import com.awesome.warehouse.repository.purchasing.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchasingServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 12, 0);
    private static final UUID REQUEST = UUID.fromString("2f1c8a8e-3b0e-4d39-9d43-0a6d6f7f0a11");

    @Mock private SupplierRepository supplierRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseReceiptRepository receiptRepository;
    @Mock private BackendClient backendClient;

    private PurchasingService service;
    private PurchaseOrderEntity purchaseOrder;
    private SupplierEntity supplier;

    @BeforeEach
    void setUp() {
        service = new PurchasingService(supplierRepository, purchaseOrderRepository, receiptRepository, backendClient,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        supplier = SupplierEntity.builder().id(1L).name("Nordic").leadTimeDays(7).build();
        purchaseOrder = PurchaseOrderEntity.builder().id(9L).supplier(supplier).status(PurchaseOrderStatus.SUBMITTED)
                .lines(new ArrayList<>()).build();
        purchaseOrder.addLine(line(3L, 10, 0));
        purchaseOrder.addLine(line(4L, 5, 5));
        lenient().when(purchaseOrderRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(purchaseOrder));
        lenient().when(purchaseOrderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static PurchaseOrderLineEntity line(Long productId, int ordered, int received) {
        return PurchaseOrderLineEntity.builder().productId(productId).productName("P" + productId)
                .orderedQuantity(ordered).receivedQuantity(received).unitCost(new BigDecimal("8.00")).build();
    }

    private static ReceiptRequestDto receipt(UUID requestId, ReceiptLineDto... lines) {
        return new ReceiptRequestDto(requestId, List.of(lines));
    }

    @Test
    void partialReceiptBooksStockThenStoresQuantitiesAndReceipt() {
        PurchaseOrderDto dto = service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 4)), "admin");

        ArgumentCaptor<InventoryReceiptRequest> booked = ArgumentCaptor.forClass(InventoryReceiptRequest.class);
        verify(backendClient).receiveInventory(booked.capture());
        assertThat(booked.getValue()).isEqualTo(new InventoryReceiptRequest(
                PurchasingService.backendRequestId(9L, REQUEST), ReceiptType.PURCHASE_RECEIVED, null,
                "Purchase order #9", List.of(new ReceiptLine(3L, 4))));
        assertThat(dto.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(dto.getLines().getFirst().getReceivedQuantity()).isEqualTo(4);
        assertThat(dto.getLines().getFirst().getOutstandingQuantity()).isEqualTo(6);
        assertThat(purchaseOrder.getReceivedAt()).isNull();
        ArgumentCaptor<PurchaseReceiptEntity> stored = ArgumentCaptor.forClass(PurchaseReceiptEntity.class);
        verify(receiptRepository).save(stored.capture());
        assertThat(stored.getValue().getRequestId()).isEqualTo(REQUEST);
        assertThat(stored.getValue().getReceivedBy()).isEqualTo("admin");
        assertThat(stored.getValue().getLinesFingerprint())
                .isEqualTo(PurchasingService.fingerprint(new TreeMap<>(Map.of(3L, 4))));
    }

    @Test
    void receivingTheLastOutstandingUnitsCompletesThePurchaseOrder() {
        PurchaseOrderDto dto = service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 10)), "admin");

        assertThat(dto.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(dto.getReceivedAt()).isEqualTo(NOW);
    }

    @Test
    void overReceiptIsAConflictAndBooksNothing() {
        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 11)), "admin"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage())
                            .isEqualTo("Receiving 11 units of product 3 exceeds the outstanding quantity of 10");
                });
        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(4L, 1)), "admin"))
                .hasMessage("Receiving 1 units of product 4 exceeds the outstanding quantity of 0");
        verify(backendClient, never()).receiveInventory(any());
        assertThat(purchaseOrder.getLines().getFirst().getReceivedQuantity()).isZero();
    }

    @Test
    void identicalReplayReturnsCurrentStateWithoutBookingAgain() {
        String fingerprint = PurchasingService.fingerprint(new TreeMap<>(Map.of(3L, 4)));
        when(receiptRepository.findByPurchaseOrderIdAndRequestId(9L, REQUEST)).thenReturn(Optional.of(
                PurchaseReceiptEntity.builder().requestId(REQUEST).linesFingerprint(fingerprint).build()));
        purchaseOrder.setStatus(PurchaseOrderStatus.RECEIVED);

        PurchaseOrderDto dto = service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 4)), "admin");

        assertThat(dto.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        verify(backendClient, never()).receiveInventory(any());
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void reusingARequestIdWithDifferentLinesIsAConflict() {
        when(receiptRepository.findByPurchaseOrderIdAndRequestId(9L, REQUEST)).thenReturn(Optional.of(
                PurchaseReceiptEntity.builder().requestId(REQUEST)
                        .linesFingerprint(PurchasingService.fingerprint(new TreeMap<>(Map.of(3L, 4)))).build()));

        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 5)), "admin"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("requestId already used with different lines");
                });
        verify(backendClient, never()).receiveInventory(any());
    }

    @Test
    void fingerprintIgnoresLineOrderButNotQuantities() {
        Map<Long, Integer> forward = new LinkedHashMap<>();
        forward.put(3L, 1);
        forward.put(4L, 2);
        String sorted = PurchasingService.fingerprint(new TreeMap<>(forward));

        assertThat(sorted).hasSize(64).isEqualTo(PurchasingService.fingerprint(new TreeMap<>(Map.of(4L, 2, 3L, 1))));
        assertThat(sorted).isNotEqualTo(PurchasingService.fingerprint(new TreeMap<>(Map.of(4L, 1, 3L, 2))));
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"DRAFT", "RECEIVED", "CANCELLED"})
    void onlyOpenPurchaseOrdersAcceptReceipts(PurchaseOrderStatus status) {
        purchaseOrder.setStatus(status);

        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 1)), "admin"))
                .hasMessage("Cannot receive goods for a purchase order in status " + status);
    }

    @Test
    void receiptLinesMustBeUniqueAndOnThePurchaseOrder() {
        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 1),
                new ReceiptLineDto(3L, 1)), "admin")).hasMessage("Product 3 is listed more than once");
        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(99L, 1)), "admin"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Product 99 is not on this purchase order");
                });
    }

    @Test
    void failedStockBookingLeavesQuantitiesAndReceiptsUntouched() {
        when(backendClient.receiveInventory(any()))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.receive(9L, receipt(REQUEST, new ReceiptLineDto(3L, 4)), "admin"))
                .hasMessage("Backend unavailable");
        assertThat(purchaseOrder.getLines().getFirst().getReceivedQuantity()).isZero();
        assertThat(purchaseOrder.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void submitSetsExpectedDateFromSupplierLeadTime() {
        purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);

        PurchaseOrderDto dto = service.submit(9L);

        assertThat(dto.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
        assertThat(dto.getSubmittedAt()).isEqualTo(NOW);
        assertThat(dto.getExpectedAt()).isEqualTo(NOW.plusDays(7));
        assertThat(dto.getTotalCost()).isEqualByComparingTo("120.00");
    }

    @Test
    void submitAndCancelGuardTheirStatuses() {
        assertThatThrownBy(() -> service.submit(9L)).hasMessage("Only draft purchase orders can be submitted");
        purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);
        purchaseOrder.getLines().clear();
        assertThatThrownBy(() -> service.submit(9L)).hasMessage("A purchase order needs at least one line");
        purchaseOrder.setStatus(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThatThrownBy(() -> service.cancel(9L))
                .hasMessage("Only draft or submitted purchase orders can be cancelled");
        purchaseOrder.setStatus(PurchaseOrderStatus.SUBMITTED);
        PurchaseOrderDto cancelled = service.cancel(9L);
        assertThat(cancelled.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW);
    }

    @Test
    void draftsSnapshotProductNamesFromTheBackend() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));
        when(backendClient.getProducts(java.util.Set.of(3L)))
                .thenReturn(List.of(new InternalProductDto(3L, "Phone", "Electronics", BigDecimal.TEN, 1)));
        when(purchaseOrderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrderDto dto = service.create(new PurchaseOrderRequestDto(1L, "  ",
                List.of(new PurchaseOrderLineRequestDto(3L, 2, new BigDecimal("5.00")))), "admin");

        assertThat(dto.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getProductName()).isEqualTo("Phone");
            assertThat(l.getLineTotal()).isEqualByComparingTo("10.00");
        });
        assertThat(dto.getNote()).isNull();
        assertThat(dto.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(dto.getCreatedBy()).isEqualTo("admin");
    }

    @Test
    void draftNotesAreTrimmedAndMissingNotesStayEmpty() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));
        when(backendClient.getProducts(java.util.Set.of())).thenReturn(List.of());
        when(purchaseOrderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(new PurchaseOrderRequestDto(1L, "  Restock  ", List.of()), "admin").getNote())
                .isEqualTo("Restock");
        assertThat(service.create(new PurchaseOrderRequestDto(1L, null, null), "admin").getNote()).isNull();
    }

    @Test
    void updateReplacesTheLinesOfADraft() {
        purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));
        when(backendClient.getProducts(java.util.Set.of(5L)))
                .thenReturn(List.of(new InternalProductDto(5L, "Tablet", "Electronics", BigDecimal.TEN, 1)));

        PurchaseOrderDto dto = service.update(9L, new PurchaseOrderRequestDto(1L, "v2",
                List.of(new PurchaseOrderLineRequestDto(5L, 3, new BigDecimal("2.50")))));

        assertThat(dto.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getProductId()).isEqualTo(5L);
            assertThat(l.getProductName()).isEqualTo("Tablet");
            assertThat(l.getOrderedQuantity()).isEqualTo(3);
        });
        assertThat(dto.getNote()).isEqualTo("v2");
        verify(purchaseOrderRepository).flush();
        purchaseOrder.setStatus(PurchaseOrderStatus.SUBMITTED);
        assertThatThrownBy(() -> service.update(9L, new PurchaseOrderRequestDto(1L, null, List.of())))
                .hasMessage("Only draft purchase orders can be edited");
    }

    @Test
    void supplierNamesAreTrimmedAndUniqueIgnoringCase() {
        when(supplierRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.createSupplier(new com.awesome.warehouse.dto.purchasing.SupplierRequestDto(
                " Nordic ", " a@b.test ", 3))).satisfies(dto -> {
                    assertThat(dto.getName()).isEqualTo("Nordic");
                    assertThat(dto.getEmail()).isEqualTo("a@b.test");
                    assertThat(dto.getLeadTimeDays()).isEqualTo(3);
                });
        when(supplierRepository.existsByNameIgnoreCase("Nordic")).thenReturn(true);
        assertThatThrownBy(() -> service.createSupplier(new com.awesome.warehouse.dto.purchasing.SupplierRequestDto(
                "Nordic", "a@b.test", 3)))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unknownProductsAreRejectedBeforeTheDraftChanges() {
        purchaseOrder.setStatus(PurchaseOrderStatus.DRAFT);
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));
        when(backendClient.getProducts(java.util.Set.of(77L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(9L, new PurchaseOrderRequestDto(1L, null,
                List.of(new PurchaseOrderLineRequestDto(77L, 2, BigDecimal.ONE)))))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Product not found");
                });
        assertThat(purchaseOrder.getLines()).hasSize(2);
    }

    @Test
    void reorderSuggestionsIncludeProjectedStockAtTheThreshold() {
        List<Object[]> incoming = new ArrayList<>();
        incoming.add(new Object[] {2L, 3L});
        when(purchaseOrderRepository.sumOutstandingByProduct(any())).thenReturn(incoming);
        when(backendClient.getAllProducts()).thenReturn(List.of(
                new InternalProductDto(1L, "Over", "C", BigDecimal.ONE, 6),
                new InternalProductDto(2L, "AtThreshold", "C", BigDecimal.ONE, 2),
                new InternalProductDto(3L, "Empty", "C", BigDecimal.ONE, 0),
                new InternalProductDto(4L, "AlsoEmpty", "C", BigDecimal.ONE, 0)));

        List<ReorderSuggestionDto> suggestions = service.reorderSuggestions(5);

        assertThat(suggestions).extracting(ReorderSuggestionDto::getProductId).containsExactly(3L, 4L, 2L);
        assertThat(suggestions.getLast().getIncomingQuantity()).isEqualTo(3);
        assertThat(suggestions.getLast().getSuggestedQuantity()).isEqualTo(15);
        assertThat(suggestions.getFirst().getSuggestedQuantity()).isEqualTo(20);
        assertThatThrownBy(() -> service.reorderSuggestions(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.reorderSuggestions(1)).extracting(ReorderSuggestionDto::getProductId).containsExactly(3L, 4L);
    }

    @Test
    void backendReceiptIdsAreScopedToThePurchaseOrder() {
        UUID clientId = UUID.randomUUID();

        assertThat(PurchasingService.backendRequestId(1L, clientId)).isEqualTo(PurchasingService.backendRequestId(1L, clientId));
        assertThat(PurchasingService.backendRequestId(1L, clientId)).isNotEqualTo(PurchasingService.backendRequestId(2L, clientId));
        assertThat(PurchasingService.backendRequestId(1L, clientId)).isNotEqualTo(clientId);
    }
}
