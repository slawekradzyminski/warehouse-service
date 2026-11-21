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
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.entity.stock.StockReservationEntity;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class StockServiceTest {

    private static final UUID RESERVATION = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");

    private final InMemoryStock stock = new InMemoryStock();
    private final StockService service = new StockService(stock.items, stock.movements, stock.reservations,
            stock.outboxRows, stock.ledger, stock.readiness, TracingSupport.disabled(), stock.transactions);

    // --- reserve -------------------------------------------------------------------------------

    @Test
    void reserveDeductsEveryLineAndStoresThePendingReservation() {
        stock.item(1, 10, 4);
        stock.item(2, 3, 1);

        ReservationDto reserved = new CorrelationContext("checkout-1", "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331").call(() -> service.reserve(request(RESERVATION, line(2, 3), line(1, 2))));

        assertThat(reserved).isEqualTo(new ReservationDto(RESERVATION,
                List.of(new ReservedLineDto(1L, 8, 5), new ReservedLineDto(2L, 0, 2))));
        assertThat(stock.lockOrder).containsExactly(1L, 2L);
        assertThat(stock.movementRows).extracting(StockMovementEntity::getProductId).containsExactly(1L, 2L);
        assertThat(stock.movementRows).allSatisfy(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.ORDER_DEDUCTED);
            assertThat(movement.getActor()).isEqualTo("alice");
            assertThat(movement.getReason()).isEqualTo("Order deduction");
            assertThat(movement.getOrderId()).isNull();
            assertThat(movement.getReservationId()).isEqualTo(RESERVATION);
            assertThat(movement.getRequestId()).isNull();
        });
        assertThat(stock.movementRows).extracting(StockMovementEntity::getDelta).containsExactly(-2, -3);
        StockReservationEntity stored = stock.reservationRows.get(RESERVATION);
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
        assertThat(stored.getUsername()).isEqualTo("alice");
        assertThat(stored.getCreatedAt()).isEqualTo(InMemoryStock.NOW);
        assertThat(stored.getCorrelationId()).isEqualTo("checkout-1");
        assertThat(stored.getTraceparent()).startsWith("00-0af7651916cd43dd8448eb211c80319c-");
        assertThat(stored.getLines()).extracting(line -> line.getQuantity()).containsExactly(2, 3);
    }

    @Test
    void aReplayReturnsTheStoredReservationWithoutDeductingAgain() {
        stock.item(1, 10, 1);
        ReservationDto first = service.reserve(request(RESERVATION, line(1, 2)));

        ReservationDto replay = service.reserve(request(RESERVATION, line(1, 2)));

        assertThat(replay).isEqualTo(first);
        assertThat(stock.movementRows).hasSize(1);
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(8);
    }

    @Test
    void aReplayWithOtherLinesIsAConflictWithoutProduct() {
        stock.item(1, 10, 1);
        stock.item(2, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2)));

        assertConflict(() -> service.reserve(request(RESERVATION, line(1, 3))),
                "reservationId already used with different lines", null);
        assertConflict(() -> service.reserve(request(RESERVATION, line(1, 2), line(2, 1))),
                "reservationId already used with different lines", null);
        assertThat(stock.movementRows).hasSize(1);
    }

    @Test
    void theWholeStockCanBeReservedButNotOneUnitMore() {
        stock.item(1, 2, 1);
        stock.item(2, 2, 1);

        assertConflict(() -> service.reserve(request(UUID.randomUUID(), line(1, 2), line(2, 3))),
                "Insufficient stock for product 2", 2L);
        assertThat(stock.movementRows).isEmpty();
        service.reserve(request(RESERVATION, line(1, 2), line(2, 2)));
        assertThat(stock.itemRows.get(2L).getQuantity()).isZero();
    }

    @Test
    void theFirstShortProductInIdOrderIsReported() {
        stock.item(1, 1, 1);
        stock.item(2, 1, 1);

        assertConflict(() -> service.reserve(request(RESERVATION, line(2, 5), line(1, 5))),
                "Insufficient stock for product 1", 1L);
    }

    @Test
    void aProductWithoutItemIs404AndNothingIsDeducted() {
        stock.item(2, 10, 1);

        assertThatThrownBy(() -> service.reserve(request(RESERVATION, line(2, 1), line(5, 1), line(3, 1))))
                .isInstanceOfSatisfying(StockException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Product 3 not found");
                    assertThat(e.getProductId()).isEqualTo(3L);
                });
        assertThat(stock.movementRows).isEmpty();
        verify(stock.reservations, never()).saveAndFlush(any());
    }

    @Test
    void aProductListedTwiceIsRejected() {
        stock.item(1, 10, 1);

        assertThatThrownBy(() -> service.reserve(request(RESERVATION, line(1, 1), line(1, 2))))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Product 1 is listed more than once");
                });
    }

    @Test
    void aConcurrentReserveWithTheSameIdIsAConflict() {
        stock.item(1, 10, 1);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate"))
                .when(stock.reservations).saveAndFlush(any());

        assertConflict(() -> service.reserve(request(RESERVATION, line(1, 1))),
                "reservationId already used with different lines", null);
    }

    @Test
    void everyOperationNeedsAnInitialisedLedger() {
        stock.readiness.markNotReady();

        List<Runnable> operations = List.of(
                () -> service.reserve(request(RESERVATION, line(1, 1))),
                () -> service.confirm(RESERVATION, 1),
                () -> service.release(RESERVATION),
                () -> service.releaseExpired(RESERVATION),
                () -> service.confirmUnconfirmed(RESERVATION, 1),
                () -> service.adjust(adjustment(1, 1, "r", UUID.randomUUID())),
                () -> service.createItem(1L, new InitialStockRequestDto(1, "admin", null)),
                () -> service.deleteItem(1L),
                () -> service.item(1L),
                () -> service.movements(1L, 0, 20));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(CustomException.class,
                    e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
        assertThat(stock.lockOrder).isEmpty();
    }

    // --- confirm -------------------------------------------------------------------------------

    @Test
    void confirmMarksTheReservationAndItsMovementsWithTheOrder() {
        stock.item(1, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2)));
        stock.item(2, 10, 1);
        service.reserve(request(UUID.randomUUID(), line(2, 1)));
        stock.reservationRows.get(RESERVATION).setUpdatedAt(InMemoryStock.NOW.minusDays(1));

        ReservationDto confirmed = service.confirm(RESERVATION, 123);

        assertThat(confirmed.lines()).containsExactly(new ReservedLineDto(1L, 8, 2));
        StockReservationEntity reservation = stock.reservationRows.get(RESERVATION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getOrderId()).isEqualTo(123L);
        assertThat(reservation.getUpdatedAt()).isEqualTo(InMemoryStock.NOW);
        assertThat(stock.movementsOf(1)).extracting(StockMovementEntity::getOrderId).containsExactly(123L);
        assertThat(stock.movementsOf(2)).extracting(StockMovementEntity::getOrderId).containsOnlyNulls();
        verify(stock.reservations).save(reservation);
    }

    @Test
    void confirmIsIdempotentForTheSameOrderOnly() {
        stock.item(1, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2)));
        service.confirm(RESERVATION, 123);

        assertThat(service.confirm(RESERVATION, 123).reservationId()).isEqualTo(RESERVATION);
        assertThatThrownBy(() -> service.confirm(RESERVATION, 124))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Reservation already confirmed for another order");
                });
        assertThat(stock.reservationRows.get(RESERVATION).getOrderId()).isEqualTo(123L);
    }

    @Test
    void confirmOfAReleasedOrUnknownReservationFails() {
        stock.item(1, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2)));
        service.release(RESERVATION);

        assertThatThrownBy(() -> service.confirm(RESERVATION, 1)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("Reservation already released");
        });
        assertThatThrownBy(() -> service.confirm(UUID.randomUUID(), 1)).isInstanceOfSatisfying(CustomException.class,
                e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Reservation not found");
                });
    }

    // --- release -------------------------------------------------------------------------------

    @Test
    void releasePutsEveryLineBackAsTheSystemOnce() {
        stock.item(1, 10, 1);
        stock.item(2, 5, 1);
        service.reserve(request(RESERVATION, line(1, 2), line(2, 5)));
        stock.reservationRows.get(RESERVATION).setUpdatedAt(InMemoryStock.NOW.minusDays(1));

        ReleasedReservationDto released = service.release(RESERVATION);
        ReleasedReservationDto again = service.release(RESERVATION);

        assertThat(released).isEqualTo(new ReleasedReservationDto(RESERVATION, ReservationStatus.RELEASED));
        assertThat(again).isEqualTo(released);
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(10);
        assertThat(stock.itemRows.get(2L).getQuantity()).isEqualTo(5);
        assertThat(stock.movementRows).filteredOn(m -> m.getType() == InventoryMovementType.ORDER_RESTORED)
                .hasSize(2).allSatisfy(movement -> {
                    assertThat(movement.getActor()).isEqualTo("system");
                    assertThat(movement.getReason()).isEqualTo("Reservation released");
                    assertThat(movement.getReservationId()).isEqualTo(RESERVATION);
                    assertThat(movement.getOrderId()).isNull();
                })
                .extracting(StockMovementEntity::getDelta).containsExactly(2, 5);
        StockReservationEntity reservation = stock.reservationRows.get(RESERVATION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.getUpdatedAt()).isEqualTo(InMemoryStock.NOW);
        verify(stock.reservations).save(reservation);
    }

    @Test
    void releaseOfAnUnknownIdStoresATombstoneThatALateReserveCannotUse() {
        stock.item(1, 10, 1);

        ReleasedReservationDto released = service.release(RESERVATION);

        assertThat(released).isEqualTo(new ReleasedReservationDto(RESERVATION, ReservationStatus.RELEASED));
        StockReservationEntity tombstone = stock.reservationRows.get(RESERVATION);
        assertThat(tombstone.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(tombstone.getLines()).isEmpty();
        assertThat(tombstone.getUsername()).isEqualTo("system");
        assertThat(service.release(RESERVATION)).isEqualTo(released);
        assertThatThrownBy(() -> service.reserve(request(RESERVATION, line(1, 2))))
                .isInstanceOfSatisfying(StockException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Reservation already released");
                });
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(10);
        assertThat(stock.movementRows).isEmpty();
    }

    @Test
    void aReserveThatCommitsTheIdFirstTurnsTheTombstoneIntoAConflict() {
        doThrow(new DataIntegrityViolationException("duplicate key")).when(stock.reservations).saveAndFlush(any());

        assertThatThrownBy(() -> service.release(RESERVATION)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("Reservation was created concurrently; release it again");
        });
    }

    @Test
    void aReleasedReservationIsNotReplayedAsHeldStock() {
        stock.item(1, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2)));
        service.release(RESERVATION);

        assertThatThrownBy(() -> service.reserve(request(RESERVATION, line(1, 2))))
                .isInstanceOfSatisfying(StockException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Reservation already released"));
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(10);
    }

    @Test
    void releaseSkipsLinesWhoseItemIsGoneOrWouldOverflow() {
        stock.item(1, 10, 1);
        stock.item(2, 10, 1);
        stock.item(3, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2), line(2, 2), line(3, 2)));
        stock.itemRows.remove(1L);
        stock.itemRows.get(2L).setQuantity(Integer.MAX_VALUE - 1);
        stock.itemRows.get(3L).setQuantity(Integer.MAX_VALUE - 2);

        service.release(RESERVATION);

        assertThat(stock.movementRows).filteredOn(m -> m.getType() == InventoryMovementType.ORDER_RESTORED)
                .extracting(StockMovementEntity::getProductId).containsExactly(3L);
        assertThat(stock.itemRows.get(3L).getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(stock.reservationRows.get(RESERVATION).getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void aConfirmedReservationIsNotReleased() {
        stock.item(1, 10, 1);
        service.reserve(request(RESERVATION, line(1, 2)));
        service.confirm(RESERVATION, 5);

        assertThatThrownBy(() -> service.release(RESERVATION)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo("Reservation already confirmed");
        });
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(8);
    }

    @Test
    void theReaperReleasesOrConfirmsOnlyPendingReservations() {
        stock.item(1, 10, 1);
        UUID expired = UUID.randomUUID();
        UUID saved = UUID.randomUUID();
        service.reserve(request(expired, line(1, 2)));
        service.reserve(request(saved, line(1, 3)));

        assertThat(service.releaseExpired(expired)).isTrue();
        assertThat(service.releaseExpired(expired)).isFalse();
        assertThat(service.confirmUnconfirmed(expired, 9)).isFalse();
        assertThat(service.confirmUnconfirmed(saved, 9)).isTrue();
        assertThat(service.confirmUnconfirmed(saved, 9)).isFalse();
        assertThat(service.releaseExpired(saved)).isFalse();
        assertThat(service.releaseExpired(UUID.randomUUID())).isFalse();
        assertThat(service.confirmUnconfirmed(UUID.randomUUID(), 9)).isFalse();

        assertThat(stock.movementRows).filteredOn(m -> m.getType() == InventoryMovementType.ORDER_RESTORED)
                .singleElement().satisfies(m -> assertThat(m.getReason()).isEqualTo("Reservation expired unconfirmed"));
        assertThat(stock.reservationRows.get(saved).getOrderId()).isEqualTo(9L);
        assertThat(stock.itemRows.get(1L).getQuantity()).isEqualTo(7);
    }

    // --- adjust --------------------------------------------------------------------------------

    @Test
    void adjustRecordsAnAdminMovementWithItsLedgerVersion() {
        stock.item(1, 10, 4);
        UUID request = UUID.randomUUID();

        StockAdjustmentDto adjusted = service.adjust(adjustment(1, -3, "Damaged", request));

        assertThat(adjusted).isEqualTo(new StockAdjustmentDto(adjusted.id(), 1L, null,
                InventoryMovementType.ADMIN_ADJUSTMENT, -3, 7, "admin", "Damaged", request, InMemoryStock.NOW, 5));
        assertThat(service.adjust(adjustment(1, -3, "Damaged", request))).isEqualTo(adjusted);
        assertThat(stock.movementRows).hasSize(1);
    }

    @Test
    void aReplayWithAnotherDeltaOrReasonIsAConflict() {
        stock.item(1, 10, 1);
        UUID request = UUID.randomUUID();
        service.adjust(adjustment(1, -3, "Damaged", request));

        assertThatThrownBy(() -> service.adjust(adjustment(1, -2, "Damaged", request)))
                .hasMessage("requestId already used with different payload");
        assertThatThrownBy(() -> service.adjust(adjustment(1, -3, "Lost", request)))
                .hasMessage("requestId already used with different payload");
        assertThat(service.adjust(adjustment(1, 1, "Found", UUID.randomUUID())).quantityAfter()).isEqualTo(8);
    }

    @Test
    void aReplayOfAnImportedMovementAnswersVersionZero() {
        stock.item(1, 10, 1);
        UUID request = UUID.randomUUID();
        stock.movementRows.add(StockMovementEntity.builder().id(5L).productId(1L)
                .type(InventoryMovementType.ADMIN_ADJUSTMENT).delta(2).quantityAfter(10).actor("admin").reason("Found")
                .requestId(request).createdAt(InMemoryStock.NOW.minusDays(3)).build());

        assertThat(service.adjust(adjustment(1, 2, "Found", request)).ledgerVersion()).isZero();
    }

    @Test
    void adjustKeepsTheQuantityBetweenZeroAndTheMaximum() {
        stock.item(1, 10, 1);
        stock.item(2, Integer.MAX_VALUE - 10, 1);

        assertThatThrownBy(() -> service.adjust(adjustment(1, -11, "r", UUID.randomUUID())))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Insufficient stock");
                });
        assertThatThrownBy(() -> service.adjust(adjustment(2, 11, "r", UUID.randomUUID())))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Inventory quantity exceeds supported maximum");
                });
        assertThat(service.adjust(adjustment(1, -10, "r", UUID.randomUUID())).quantityAfter()).isZero();
        assertThat(service.adjust(adjustment(2, 10, "r", UUID.randomUUID())).quantityAfter())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void adjustRejectsAZeroDeltaAndAnUnknownProduct() {
        assertThatThrownBy(() -> service.adjust(adjustment(1, 0, "r", UUID.randomUUID())))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Inventory delta must not be zero");
                });
        assertThatThrownBy(() -> service.adjust(adjustment(1, 1, "r", UUID.randomUUID())))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Product not found");
                });
    }

    // --- items ---------------------------------------------------------------------------------

    @Test
    void createItemCreatesOnceAndOtherwiseReturnsTheExistingItem() {
        StockService.CreatedItem created = service.createItem(7L, new InitialStockRequestDto(10, "admin", null));
        StockService.CreatedItem existing = service.createItem(7L, new InitialStockRequestDto(99, "admin", null));

        assertThat(created).isEqualTo(new StockService.CreatedItem(new StockItemDto(7L, 10, 1), true));
        assertThat(existing).isEqualTo(new StockService.CreatedItem(new StockItemDto(7L, 10, 1), false));
        assertThat(stock.movementsOf(7)).hasSize(1);
    }

    @Test
    void aConcurrentCreateReturnsTheItemTheOtherCallCreated() {
        org.mockito.Mockito.doAnswer(invocation -> {
            stock.item(7, 3, 1);
            throw new DataIntegrityViolationException("duplicate");
        }).when(stock.items).saveAndFlush(any());

        assertThat(service.createItem(7L, new InitialStockRequestDto(10, "admin", null)))
                .isEqualTo(new StockService.CreatedItem(new StockItemDto(7L, 3, 1), false));
    }

    @Test
    void deleteRemovesTheProductsLedgerRowsAndTheItem() {
        stock.item(1, 10, 1);

        service.deleteItem(1L);
        service.deleteItem(2L);

        InOrder order = inOrder(stock.items, stock.reservations, stock.outboxRows, stock.movements);
        order.verify(stock.items).findForUpdate(1L);
        order.verify(stock.reservations).deleteLinesOfProduct(1L);
        order.verify(stock.reservations).deleteWithoutLines();
        order.verify(stock.outboxRows).deleteByProduct(1L);
        order.verify(stock.movements).deleteByProduct(1L);
        order.verify(stock.items).delete(stock.itemRows.get(1L));
        verify(stock.movements).deleteByProduct(2L);
        verify(stock.items, org.mockito.Mockito.times(1)).delete(any());
    }

    @Test
    void itemReturnsTheQuantityAndVersionOr404() {
        stock.item(1, 6, 9);

        assertThat(service.item(1L)).isEqualTo(new StockItemDto(1L, 6, 9));
        assertThatThrownBy(() -> service.item(2L)).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("Product not found");
        });
    }

    @Test
    void movementsAreMappedAndThePageIsClamped() {
        stock.item(1, 10, 1);
        UUID request = UUID.randomUUID();
        service.adjust(adjustment(1, -1, "One", request));
        service.adjust(adjustment(1, -2, "Two", UUID.randomUUID()));

        PageDto<InventoryMovementDto> page = service.movements(1L, -4, 1);
        PageDto<InventoryMovementDto> wide = service.movements(1L, 0, 1000);
        PageDto<InventoryMovementDto> narrow = service.movements(1L, 1, 0);

        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).singleElement().satisfies(movement -> assertThat(movement.delta()).isEqualTo(-2));
        assertThat(wide.getPageSize()).isEqualTo(100);
        assertThat(wide.getContent().getLast()).isEqualTo(new InventoryMovementDto(wide.getContent().getLast().id(),
                1L, null, InventoryMovementType.ADMIN_ADJUSTMENT, -1, 9, "admin", "One", request, InMemoryStock.NOW));
        assertThat(narrow.getPageSize()).isEqualTo(1);
        assertThat(narrow.getPageNumber()).isEqualTo(1);
        assertThat(narrow.getContent()).singleElement().satisfies(movement -> assertThat(movement.delta()).isEqualTo(-1));
    }

    private static ReserveStockRequestDto request(UUID reservationId, StockLineDto... lines) {
        return new ReserveStockRequestDto(reservationId, "alice", List.of(lines));
    }

    private static StockLineDto line(long productId, int quantity) {
        return new StockLineDto(productId, quantity);
    }

    private static StockAdjustmentRequestDto adjustment(long productId, int delta, String reason, UUID requestId) {
        return new StockAdjustmentRequestDto(productId, delta, reason, requestId, "admin");
    }

    private static void assertConflict(Runnable call, String message, Long productId) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StockException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).isEqualTo(message);
            assertThat(e.getProductId()).isEqualTo(productId);
        });
    }
}
