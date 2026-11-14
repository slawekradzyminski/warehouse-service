package com.awesome.warehouse.service.stock;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.entity.stock.StockRestoreEntity;
import com.awesome.warehouse.repository.stock.StockRestoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockRestoreConsumerTest {

    private final InMemoryStock stock = new InMemoryStock();
    private final StockRestoreRepository restores = mock(StockRestoreRepository.class);
    private final StockRestoreConsumer consumer = new StockRestoreConsumer(restores, stock.ledger, stock.readiness,
            stock.transactions);

    @BeforeEach
    void setUp() {
        when(restores.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static FeedEvent cancelled(Long orderId, Map<String, Object> data) {
        return new FeedEvent(1, UUID.randomUUID(), "order.cancelled", Instant.EPOCH, orderId, "alice", data);
    }

    private static Map<String, Object> withRestore(Object stockRestore) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", 42);
        data.put("stockRestore", stockRestore);
        return data;
    }

    private static Map<String, Object> restore(String actor, Object... lines) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            list.add(Map.of("productId", lines[i], "quantity", lines[i + 1]));
        }
        return Map.of("actor", actor, "lines", list);
    }

    @Test
    void everyLineIsRestoredInProductIdOrderWithTheOrderAndTheEventsActor() {
        stock.item(1, 0, 3);
        stock.item(2, 5, 1);
        FeedEvent event = cancelled(42L, withRestore(restore("alice", 2, 3, 1, 2)));

        assertThat(consumer.restore(event)).isTrue();

        assertThat(stock.movementRows).extracting(StockMovementEntity::getProductId).containsExactly(1L, 2L);
        assertThat(stock.movementRows).extracting(StockMovementEntity::getDelta).containsExactly(2, 3);
        assertThat(stock.movementRows).allSatisfy(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.ORDER_RESTORED);
            assertThat(movement.getOrderId()).isEqualTo(42L);
            assertThat(movement.getActor()).isEqualTo("alice");
            assertThat(movement.getReason()).isEqualTo("Order cancellation");
            assertThat(movement.getReservationId()).isNull();
            assertThat(movement.getRequestId()).isNull();
        });
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(2);
        assertThat(stock.itemRows.get(2L).getQuantity()).isEqualTo(8);
        ArgumentCaptor<StockRestoreEntity> stored = ArgumentCaptor.forClass(StockRestoreEntity.class);
        verify(restores).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getOrderId()).isEqualTo(42L);
        assertThat(stored.getValue().getEventId()).isEqualTo(event.id());
        assertThat(stored.getValue().getRestoredAt()).isEqualTo(InMemoryStock.NOW);
    }

    @Test
    void anOrderIsRestoredOnlyOnce() {
        stock.item(1, 0, 1);
        when(restores.existsById(42L)).thenReturn(true);

        assertThat(consumer.restore(cancelled(42L, withRestore(restore("alice", 1, 2))))).isFalse();

        assertThat(stock.movementRows).isEmpty();
        verify(restores, never()).saveAndFlush(any());
    }

    @Test
    void aConcurrentRestoreOfTheSameOrderIsNotAnError() {
        stock.item(1, 0, 1);
        when(restores.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(consumer.restore(cancelled(42L, withRestore(restore("alice", 1, 2))))).isFalse();
    }

    @Test
    void linesWhoseItemIsGoneOrWouldOverflowAreSkipped() {
        stock.item(2, Integer.MAX_VALUE - 1, 1);
        stock.item(3, Integer.MAX_VALUE - 2, 1);

        assertThat(consumer.restore(cancelled(42L, withRestore(restore("alice", 1, 1, 2, 2, 3, 2))))).isTrue();

        assertThat(stock.movementRows).extracting(StockMovementEntity::getProductId).containsExactly(3L);
        assertThat(stock.itemRows.get(3L).getQuantity()).isEqualTo(Integer.MAX_VALUE);
        verify(restores).saveAndFlush(any());
    }

    @Test
    void anEventWithoutStockRestoreIsIgnored() {
        assertThat(consumer.restore(cancelled(42L, Map.of("orderId", 42)))).isFalse();
        stock.readiness.markNotReady();
        assertThat(consumer.restore(cancelled(42L, null))).isFalse();

        verifyNoInteractions(restores, stock.transactions);
    }

    @Test
    void aRestoreWaitsForTheLedger() {
        stock.readiness.markNotReady();

        assertThatThrownBy(() -> consumer.restore(cancelled(42L, withRestore(restore("alice", 1, 1)))))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(restores);
    }

    @Test
    void theOrderIdComesFromTheEventOrItsDataAndRepeatedProductsAreSummed() {
        StockRestoreConsumer.StockRestore fromEvent = StockRestoreConsumer.parse(
                cancelled(7L, withRestore(restore("bob", 1, 2, 1, 3)))).orElseThrow();
        StockRestoreConsumer.StockRestore fromData = StockRestoreConsumer.parse(
                cancelled(null, withRestore(restore("system", 4L, 1)))).orElseThrow();

        assertThat(fromEvent).isEqualTo(new StockRestoreConsumer.StockRestore(7L, "bob", Map.of(1L, 5)));
        assertThat(fromData).isEqualTo(new StockRestoreConsumer.StockRestore(42L, "system", Map.of(4L, 1)));
    }

    @Test
    void aMalformedStockRestoreIsRejected() {
        Map<String, Object> noOrder = new LinkedHashMap<>();
        noOrder.put("stockRestore", restore("alice", 1, 1));
        List<Object> malformed = List.of(
                "not an object",
                Map.of("lines", List.of()),
                Map.of("actor", " ", "lines", List.of()),
                Map.of("actor", 5, "lines", List.of()),
                Map.of("actor", "alice"),
                Map.of("actor", "alice", "lines", List.of("x")),
                Map.of("actor", "alice", "lines", List.of(Map.of("productId", "1", "quantity", 1))),
                Map.of("actor", "alice", "lines", List.of(Map.of("productId", 1, "quantity", "1"))),
                Map.of("actor", "alice", "lines", List.of(Map.of("productId", 1, "quantity", 0))));

        for (Object stockRestore : malformed) {
            assertThatThrownBy(() -> StockRestoreConsumer.parse(cancelled(42L, withRestore(stockRestore))))
                    .as(String.valueOf(stockRestore)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> StockRestoreConsumer.parse(cancelled(null, noOrder)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no orderId");
        assertThat(StockRestoreConsumer.parse(cancelled(42L, withRestore(restore("alice", 1, 1))))).isPresent();
    }
}
