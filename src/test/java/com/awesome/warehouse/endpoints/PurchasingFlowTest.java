package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.ReceiptType;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderLineRequestDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderRequestDto;
import com.awesome.warehouse.dto.purchasing.ReceiptLineDto;
import com.awesome.warehouse.dto.purchasing.ReceiptRequestDto;
import com.awesome.warehouse.dto.purchasing.ReorderSuggestionDto;
import com.awesome.warehouse.dto.purchasing.SupplierDto;
import com.awesome.warehouse.dto.purchasing.SupplierRequestDto;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderStatus;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchasingFlowTest extends AbstractFlowTest {

    private static final String SUPPLIERS = "/api/v1/admin/suppliers";
    private static final String POS = "/api/v1/admin/purchase-orders";

    @Test
    void purchaseOrderLifecycleBooksPartialAndFinalReceipts() {
        SupplierDto supplier = supplier("Nordic Test Supply", 7);
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 0);
        PurchaseOrderDto draft = createDraft(supplier, List.of(line(product, 10)));

        PurchaseOrderDto submitted = postJson(POS + "/" + draft.getId() + "/submit", null, adminToken)
                .as(PurchaseOrderDto.class);
        PurchaseOrderDto partial = receive(draft, UUID.randomUUID(), product, 4).as(PurchaseOrderDto.class);
        Response overReceipt = receive(draft, UUID.randomUUID(), product, 7);
        PurchaseOrderDto complete = receive(draft, UUID.randomUUID(), product, 6).as(PurchaseOrderDto.class);
        Response cancelReceived = postJson(POS + "/" + draft.getId() + "/cancel", null, adminToken);

        assertThat(draft.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(draft.getLines().getFirst().getProductName()).isEqualTo("Phone");
        assertThat(ChronoUnit.DAYS.between(submitted.getSubmittedAt(), submitted.getExpectedAt())).isEqualTo(7);
        assertThat(partial.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(partial.getLines().getFirst().getOutstandingQuantity()).isEqualTo(6);
        assertThat(overReceipt.status()).isEqualTo(409);
        assertThat(overReceipt.message())
                .isEqualTo("Receiving 7 units of product " + product.id() + " exceeds the outstanding quantity of 6");
        assertThat(complete.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(complete.getReceivedAt()).isNotNull();
        assertThat(backend.stockOf(product.id())).isEqualTo(10);
        assertThat(backend.receipts()).allSatisfy(receipt -> {
            assertThat(receipt.type()).isEqualTo(ReceiptType.PURCHASE_RECEIVED);
            assertThat(receipt.orderId()).isNull();
            assertThat(receipt.reference()).isEqualTo("Purchase order #" + draft.getId());
        });
        assertThat(cancelReceived.status()).isEqualTo(409);
    }

    @Test
    void repeatedReceiptRequestIdIsReplayedOnceAndConflictsWhenLinesDiffer() {
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 0);
        PurchaseOrderDto draft = createDraft(supplier("Replay Supply", 3), List.of(line(product, 10)));
        postJson(POS + "/" + draft.getId() + "/submit", null, adminToken);
        UUID requestId = UUID.randomUUID();

        receive(draft, requestId, product, 3);
        Response replay = receive(draft, requestId, product, 3);
        Response changed = receive(draft, requestId, product, 4);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.as(PurchaseOrderDto.class).getLines().getFirst().getReceivedQuantity()).isEqualTo(3);
        assertThat(backend.stockOf(product.id())).isEqualTo(3);
        assertThat(backend.calls()).filteredOn(call -> call.startsWith("POST receipts")).hasSize(1);
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.message()).isEqualTo("requestId already used with different lines");
    }

    @Test
    void failedStockBookingLeavesThePurchaseOrderUnchanged() {
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 0);
        PurchaseOrderDto draft = createDraft(supplier("Outage Supply", 3), List.of(line(product, 10)));
        postJson(POS + "/" + draft.getId() + "/submit", null, adminToken);
        UUID requestId = UUID.randomUUID();

        backend.setAvailable(false);
        Response failed = receive(draft, requestId, product, 3);
        backend.setAvailable(true);
        PurchaseOrderDto unchanged = getJson(POS + "/" + draft.getId(), adminToken).as(PurchaseOrderDto.class);
        Response retried = receive(draft, requestId, product, 3);

        assertThat(failed.status()).isEqualTo(503);
        assertThat(unchanged.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
        assertThat(unchanged.getLines().getFirst().getReceivedQuantity()).isZero();
        assertThat(retried.as(PurchaseOrderDto.class).getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(backend.stockOf(product.id())).isEqualTo(3);
    }

    @Test
    void draftsAreEditableButNeedLinesToSubmitAndCannotBeReceivedIntoBeforeSubmission() {
        SupplierDto supplier = supplier("Draft Supply", 0);
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 0);
        PurchaseOrderDto empty = createDraft(supplier, List.of());

        Response submitEmpty = postJson(POS + "/" + empty.getId() + "/submit", null, adminToken);
        Response receiveDraft = receive(empty, UUID.randomUUID(), product, 1);
        Response edited = putJson(POS + "/" + empty.getId(),
                new PurchaseOrderRequestDto(supplier.getId(), "now with lines", List.of(line(product, 5))), adminToken);
        Response duplicateLines = postJson(POS,
                new PurchaseOrderRequestDto(supplier.getId(), null, List.of(line(product, 1), line(product, 2))),
                adminToken);
        Response unknownProduct = postJson(POS, new PurchaseOrderRequestDto(supplier.getId(), null,
                List.of(new PurchaseOrderLineRequestDto(999_999L, 1, BigDecimal.ONE))), adminToken);
        Response unknownSupplier = postJson(POS, new PurchaseOrderRequestDto(999_999L, null, List.of()), adminToken);
        postJson(POS + "/" + empty.getId() + "/submit", null, adminToken);
        Response editSubmitted = putJson(POS + "/" + empty.getId(),
                new PurchaseOrderRequestDto(supplier.getId(), null, List.of()), adminToken);
        PageDto<PurchaseOrderDto> submitted = getJson(POS + "?status=SUBMITTED", adminToken)
                .as(new TypeReference<PageDto<PurchaseOrderDto>>() {});

        assertThat(submitEmpty.status()).isEqualTo(400);
        assertThat(submitEmpty.message()).isEqualTo("A purchase order needs at least one line");
        assertThat(receiveDraft.status()).isEqualTo(409);
        assertThat(edited.as(PurchaseOrderDto.class).getLines()).hasSize(1);
        assertThat(edited.as(PurchaseOrderDto.class).getTotalCost()).isEqualByComparingTo("40.00");
        assertThat(duplicateLines.status()).isEqualTo(400);
        assertThat(unknownProduct.status()).isEqualTo(404);
        assertThat(unknownProduct.message()).isEqualTo("Product not found");
        assertThat(unknownSupplier.message()).isEqualTo("Supplier not found");
        assertThat(editSubmitted.status()).isEqualTo(409);
        assertThat(submitted.getContent()).extracting(PurchaseOrderDto::getId).containsExactly(empty.getId());
    }

    @Test
    void supplierNamesAreUniqueIgnoringCase() {
        supplier("Unique Supply", 5);

        Response duplicate = postJson(SUPPLIERS, new SupplierRequestDto("unique supply", "x@supply.test", 5), adminToken);
        List<SupplierDto> suppliers = getJson(SUPPLIERS, adminToken).as(new TypeReference<List<SupplierDto>>() {});

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(suppliers).extracting(SupplierDto::getName).containsExactly("Unique Supply");
        assertThat(getJson(SUPPLIERS, warehouseToken).status()).isEqualTo(403);
    }

    @Test
    void reorderSuggestionsCountIncomingUnitsFromOpenPurchaseOrders() {
        InternalProductDto covered = backend.product("Covered", "Electronics", "10.00", 1);
        InternalProductDto uncovered = backend.product("Uncovered", "Electronics", "10.00", 2);
        backend.product("Plenty", "Electronics", "10.00", 50);
        PurchaseOrderDto draft = createDraft(supplier("Reorder Supply", 1), List.of(line(covered, 10)));
        postJson(POS + "/" + draft.getId() + "/submit", null, adminToken);

        List<ReorderSuggestionDto> suggestions = getJson(
                "/api/v1/admin/inventory/reorder-suggestions?lowStockThreshold=5", adminToken)
                .as(new TypeReference<List<ReorderSuggestionDto>>() {});
        Response invalid = getJson("/api/v1/admin/inventory/reorder-suggestions?lowStockThreshold=0", adminToken);

        assertThat(suggestions).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.getProductId()).isEqualTo(uncovered.id());
            assertThat(suggestion.getSuggestedQuantity()).isEqualTo(18);
        });
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("lowStockThreshold must be at least 1");
    }

    private SupplierDto supplier(String name, int leadTime) {
        Response response = postJson(SUPPLIERS, new SupplierRequestDto(name, "buyer@supply.test", leadTime), adminToken);
        assertThat(response.status()).isEqualTo(201);
        return response.as(SupplierDto.class);
    }

    private PurchaseOrderLineRequestDto line(InternalProductDto product, int quantity) {
        return new PurchaseOrderLineRequestDto(product.id(), quantity, new BigDecimal("8.00"));
    }

    private PurchaseOrderDto createDraft(SupplierDto supplier, List<PurchaseOrderLineRequestDto> lines) {
        Response response = postJson(POS, new PurchaseOrderRequestDto(supplier.getId(), null, lines), adminToken);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.as(PurchaseOrderDto.class);
    }

    private Response receive(PurchaseOrderDto po, UUID requestId, InternalProductDto product, int quantity) {
        return postJson(POS + "/" + po.getId() + "/receipts",
                new ReceiptRequestDto(requestId, List.of(new ReceiptLineDto(product.id(), quantity))), adminToken);
    }
}
