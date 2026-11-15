package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.StockItemEntity;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StockLedgerTest {

    private final InMemoryStock stock = new InMemoryStock();
    private final StockLedger ledger = stock.ledger;

    @Test
    void itemsAreLockedInAscendingProductIdOrderAndMissingOnesAreLeftOut() {
        stock.item(3, 1, 1);
        stock.item(1, 1, 1);

        Map<Long, StockItemEntity> locked = ledger.lockInOrder(List.of(3L, 2L, 1L, 3L));

        assertThat(stock.lockOrder).containsExactly(1L, 2L, 3L);
        assertThat(locked.keySet()).containsExactly(1L, 3L);
    }

    @Test
    void aChangeWritesTheMovementBumpsTheVersionAndQueuesTheLevel() {
        StockItemEntity item = stock.item(1, 10, 4);
        UUID reservation = UUID.randomUUID();
        UUID request = UUID.randomUUID();

        StockMovementEntity movement = ledger.record(item, new StockLedger.Change(
                InventoryMovementType.ORDER_DEDUCTED, -3, 9L, reservation, "alice", "Order deduction", request));

        assertThat(item.getQuantity()).isEqualTo(7);
        assertThat(item.getLedgerVersion()).isEqualTo(5);
        assertThat(item.getUpdatedAt()).isEqualTo(InMemoryStock.NOW);
        assertThat(movement.getProductId()).isEqualTo(1L);
        assertThat(movement.getType()).isEqualTo(InventoryMovementType.ORDER_DEDUCTED);
        assertThat(movement.getDelta()).isEqualTo(-3);
        assertThat(movement.getQuantityAfter()).isEqualTo(7);
        assertThat(movement.getOrderId()).isEqualTo(9L);
        assertThat(movement.getReservationId()).isEqualTo(reservation);
        assertThat(movement.getActor()).isEqualTo("alice");
        assertThat(movement.getReason()).isEqualTo("Order deduction");
        assertThat(movement.getRequestId()).isEqualTo(request);
        assertThat(movement.getLedgerVersion()).isEqualTo(5L);
        assertThat(movement.getCreatedAt()).isEqualTo(InMemoryStock.NOW);
        verify(stock.items).save(item);
        verify(stock.levelOutbox).enqueue(item);
    }

    @Test
    void aChangeToExactlyZeroOrTheMaximumIsAllowedButNotBeyond() {
        StockItemEntity empty = stock.item(1, 5, 1);
        StockItemEntity full = stock.item(2, Integer.MAX_VALUE - 5, 1);

        ledger.record(empty, change(-5));
        ledger.record(full, change(5));

        assertThat(empty.getQuantity()).isZero();
        assertThat(full.getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> ledger.record(empty, change(-1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.record(full, change(1))).isInstanceOf(IllegalStateException.class);
        assertThat(empty.getLedgerVersion()).isEqualTo(2);
    }

    @Test
    void createStartsAtVersionOneWithAnInitialStockMovementForAPositiveQuantity() {
        StockItemEntity created = ledger.create(7L, 10, "admin");
        StockItemEntity empty = ledger.create(8L, 0, "admin");

        assertThat(created.getQuantity()).isEqualTo(10);
        assertThat(created.getLedgerVersion()).isEqualTo(1);
        assertThat(created.getCreatedAt()).isEqualTo(InMemoryStock.NOW);
        assertThat(stock.movementsOf(7)).singleElement().satisfies(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.INITIAL_STOCK);
            assertThat(movement.getDelta()).isEqualTo(10);
            assertThat(movement.getQuantityAfter()).isEqualTo(10);
            assertThat(movement.getActor()).isEqualTo("admin");
            assertThat(movement.getReason()).isEqualTo("Initial stock");
            assertThat(movement.getRequestId()).isNull();
            assertThat(movement.getLedgerVersion()).isEqualTo(1L);
            assertThat(movement.getCreatedAt()).isEqualTo(InMemoryStock.NOW);
        });
        assertThat(empty.getQuantity()).isZero();
        assertThat(stock.movementsOf(8)).isEmpty();
        verify(stock.levelOutbox).enqueue(created);
        verify(stock.levelOutbox).enqueue(empty);
    }

    @Test
    void receiptsAddEveryLineInProductIdOrderAsTheWarehouseService() {
        stock.item(1, 0, 1);
        stock.item(2, 5, 3);
        UUID request = UUID.randomUUID();

        ledger.receive(new java.util.TreeMap<>(Map.of(2L, 4, 1L, 6)).descendingMap(),
                InventoryMovementType.RETURN_RESTOCKED, 42L, "Return #5", request);

        assertThat(stock.movementRows).extracting(StockMovementEntity::getProductId).containsExactly(1L, 2L);
        assertThat(stock.movementRows).allSatisfy(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.RETURN_RESTOCKED);
            assertThat(movement.getActor()).isEqualTo("warehouse-service");
            assertThat(movement.getReason()).isEqualTo("Return #5");
            assertThat(movement.getOrderId()).isEqualTo(42L);
            assertThat(movement.getRequestId()).isEqualTo(request);
            assertThat(movement.getReservationId()).isNull();
        });
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(6);
        assertThat(stock.itemRows.get(2L).getQuantity()).isEqualTo(9);
        assertThat(stock.itemRows.get(2L).getLedgerVersion()).isEqualTo(4);
    }

    @Test
    void aReceiptIsValidatedCompletelyBeforeAnythingIsWritten() {
        stock.item(1, 0, 1);
        stock.item(2, Integer.MAX_VALUE - 1, 1);

        assertThatThrownBy(() -> ledger.receive(Map.of(1L, 1, 3L, 1), InventoryMovementType.PURCHASE_RECEIVED, null,
                "Purchase order #1", null))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Product 3 not found");
                });
        assertThatThrownBy(() -> ledger.receive(Map.of(1L, 1, 2L, 2), InventoryMovementType.PURCHASE_RECEIVED, null,
                "Purchase order #1", null))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Inventory quantity exceeds supported maximum");
                });
        assertThatThrownBy(() -> ledger.receive(Map.of(1L, 0), InventoryMovementType.PURCHASE_RECEIVED, null,
                "Purchase order #1", null))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Received quantity must be positive");
                });
        java.util.Map<Long, Integer> withNull = new java.util.HashMap<>();
        withNull.put(1L, null);
        assertThatThrownBy(() -> ledger.receive(withNull, InventoryMovementType.PURCHASE_RECEIVED, null, "r", null))
                .hasMessage("Received quantity must be positive");
        verify(stock.movements, never()).save(any());
        assertThat(stock.itemRows.get(1L).getQuantity()).isZero();
    }

    @Test
    void aReceiptUpToTheMaximumIsAccepted() {
        stock.item(2, Integer.MAX_VALUE - 1, 1);

        ledger.receive(Map.of(2L, 1), InventoryMovementType.PURCHASE_RECEIVED, null, "Purchase order #1", null);

        assertThat(stock.itemRows.get(2L).getQuantity()).isEqualTo(Integer.MAX_VALUE);
        verify(stock.movements, times(1)).save(any());
    }

    @Test
    void onlyReceiptTypesAreAcceptedAndOnlyOnceTheLedgerIsReady() {
        stock.item(1, 0, 1);

        assertThatThrownBy(() -> ledger.receive(Map.of(1L, 1), InventoryMovementType.ADMIN_ADJUSTMENT, null, "r", null))
                .isInstanceOf(IllegalArgumentException.class);
        ledger.receive(Map.of(1L, 1), InventoryMovementType.PURCHASE_RECEIVED, null, "r", null);
        stock.readiness.markNotReady();
        assertThatThrownBy(() -> ledger.receive(Map.of(1L, 1), InventoryMovementType.RETURN_RESTOCKED, null, "r", null))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(1);
    }

    private static StockLedger.Change change(int delta) {
        return new StockLedger.Change(InventoryMovementType.ADMIN_ADJUSTMENT, delta, null, null, "admin", "r", null);
    }
}
