package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.ExportedMovement;
import com.awesome.warehouse.client.ExportedStockItem;
import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.StockLevel;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.stock.ReserveStockRequestDto;
import com.awesome.warehouse.dto.stock.StockAdjustmentRequestDto;
import com.awesome.warehouse.dto.stock.StockLineDto;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.ReservationStatus;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.service.event.EventFeedPoller;
import com.awesome.warehouse.service.stock.ReservationReaper;
import com.awesome.warehouse.service.stock.StockImporter;
import com.awesome.warehouse.service.stock.StockMetrics;
import com.awesome.warehouse.service.stock.StockTestAccess;
import com.awesome.warehouse.support.AbstractFlowTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The asynchronous side of the stock ledger: the startup import, restores from
 * {@code order.cancelled}, the level push to the backend projection, its metrics and the
 * reservation reaper.
 */
class StockLedgerFlowTest extends AbstractFlowTest {

    private static final LocalDateTime EXPORTED_AT = LocalDateTime.parse("2026-11-01T09:15:30.123456");

    @Autowired private StockImporter importer;
    @Autowired private EventFeedPoller poller;
    @Autowired private ReservationReaper reaper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void ledgerReady() {
        StockTestAccess.markReady(stockReadiness);
    }

    // --- one-time import (contract 6) ----------------------------------------------------------

    @Test
    void anEmptyLedgerImportsTheBackendLedgerKeepingMovementIdsAndPushesVersionOne() {
        backend.onProductCreated(product -> { });
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 7);
        InternalProductDto cable = backend.product("Cable", "Accessories", "2.00", 0);
        UUID requestId = UUID.randomUUID();
        backend.setExport(List.of(
                new ExportedStockItem(phone.id(), 7, List.of(
                        new ExportedMovement(500L, null, InventoryMovementType.INITIAL_STOCK, 10, 10, "admin",
                                "Initial stock", null, EXPORTED_AT),
                        new ExportedMovement(501L, 42L, InventoryMovementType.ADMIN_ADJUSTMENT, -3, 7, "admin",
                                "Damaged", requestId, EXPORTED_AT.plusSeconds(1)))),
                new ExportedStockItem(cable.id(), 0, List.of()),
                // A product listed twice keeps its first item.
                new ExportedStockItem(cable.id(), 5, List.of())));
        StockTestAccess.markNotReady(stockReadiness);

        int imported = importer.importLedger();
        awaitStockPush();

        assertThat(imported).isEqualTo(2);
        assertThat(stockReadiness.isReady()).isTrue();
        assertThat(stockItems.findById(phone.id()).orElseThrow()).satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(7);
            assertThat(item.getLedgerVersion()).isEqualTo(1);
        });
        assertThat(stockItems.findById(cable.id()).orElseThrow().getQuantity()).isZero();
        assertThat(movementsOf(phone.id())).extracting(StockMovementEntity::getId).containsExactly(500L, 501L);
        assertThat(movementsOf(phone.id()).getLast()).satisfies(movement -> {
            assertThat(movement.getOrderId()).isEqualTo(42L);
            assertThat(movement.getRequestId()).isEqualTo(requestId);
            assertThat(movement.getCreatedAt()).isEqualTo(EXPORTED_AT.plusSeconds(1));
            assertThat(movement.getLedgerVersion()).isNull();
        });
        assertThat(backend.stockOf(phone.id())).isEqualTo(7);
        assertThat(backend.ledgerVersionOf(phone.id())).isEqualTo(1);
        assertThat(backend.ledgerVersionOf(cable.id())).isEqualTo(1);

        // New movements continue after the largest imported id; a replay of an imported
        // adjustment answers version 0, which the projection never applies.
        long next = stockService.adjust(new StockAdjustmentRequestDto(phone.id(), 1, "Found", UUID.randomUUID(),
                "admin")).id();
        long replayVersion = stockService.adjust(new StockAdjustmentRequestDto(phone.id(), -3, "Damaged", requestId,
                "admin")).ledgerVersion();
        assertThat(next).isGreaterThan(501L);
        assertThat(replayVersion).isZero();
        assertThat(importer.importLedger()).as("a ledger with data is never imported again").isZero();
    }

    @Test
    void theExportIsReadPageByPage() {
        backend.onProductCreated(product -> { });
        List<ExportedStockItem> items = LongStream.rangeClosed(1, 501)
                .mapToObj(id -> new ExportedStockItem(800_000 + id, 1, List.of())).toList();
        backend.setExport(items);
        StockTestAccess.markNotReady(stockReadiness);

        assertThat(importer.importLedger()).isEqualTo(501);

        assertThat(backend.calls()).containsExactly("GET export after null", "GET export after 800500");
        assertThat(stockItems.count()).isEqualTo(501);
    }

    @Test
    void theImportIsRetriedUntilTheBackendAnswersAndTheStockApiWaitsForIt() throws Exception {
        backend.setAvailable(false);
        StockTestAccess.markNotReady(stockReadiness);

        assertThatThrownBy(importer::importLedger).hasMessage("Backend unavailable");
        assertThatThrownBy(() -> stockService.item(1L)).hasMessage("Stock ledger not initialised");
        Response readiness = getJson("/actuator/health/readiness", null);
        Response health = getJson("/actuator/health", null);
        importer.start();
        Thread.sleep(300);
        backend.setAvailable(true);

        assertThat(readiness.status()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"IMPORTING\"");
        assertThat(health.status()).as("the container healthcheck keeps passing").isEqualTo(200);
        assertThat(stockReadiness.awaitReady(Duration.ofSeconds(10))).isTrue();
        assertThat(getJson("/actuator/health/readiness", null).status()).isEqualTo(200);
    }

    // --- restore after cancel (contract 3.1) ---------------------------------------------------

    @Test
    void aCancellationWithStockRestoreRestoresTheOrderOnce() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        InternalProductDto cable = backend.product("Cable", "Accessories", "2.00", 4);
        InternalOrderDto order = backend.order("alice", OrderStatus.PAID, null, phone, 2, cable, 1);
        FeedEvent cancelled = backend.cancelWithStockRestore(order.id(), "alice");

        poller.poll();
        backend.publish(cancelled);
        backend.cancelWithStockRestore(order.id(), "alice");
        poller.poll();
        awaitStockPush();

        assertThat(movementsOf(phone.id())).filteredOn(m -> m.getType() == InventoryMovementType.ORDER_RESTORED)
                .singleElement().satisfies(movement -> {
                    assertThat(movement.getOrderId()).isEqualTo(order.id());
                    assertThat(movement.getActor()).isEqualTo("alice");
                    assertThat(movement.getReason()).isEqualTo("Order cancellation");
                    assertThat(movement.getDelta()).isEqualTo(2);
                    assertThat(movement.getQuantityAfter()).isEqualTo(12);
                });
        assertThat(stockItems.findById(cable.id()).orElseThrow().getQuantity()).isEqualTo(5);
        assertThat(stockRestores.findById(order.id()).orElseThrow().getEventId()).isEqualTo(cancelled.id());
        assertThat(backend.stockOf(phone.id())).isEqualTo(12);
    }

    @Test
    void aCancellationWithoutStockRestoreLeavesTheLedgerAlone() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("alice", OrderStatus.PAID, null, phone, 2);
        backend.cancelByCustomer(order.id());

        poller.poll();

        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(10);
        assertThat(stockRestores.count()).isZero();
    }

    @Test
    void aRestoreSkipsProductsThatNoLongerHaveAnItem() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        InternalProductDto gone = backend.product("Old", "Accessories", "2.00", 4);
        InternalOrderDto order = backend.order("alice", OrderStatus.PAID, null, phone, 1, gone, 1);
        stockService.deleteItem(gone.id());
        backend.cancelWithStockRestore(order.id(), "alice");

        poller.poll();

        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(11);
        assertThat(stockItems.existsById(gone.id())).isFalse();
        assertThat(stockRestores.existsById(order.id())).isTrue();
    }

    // --- level push and metrics (contract 3.3, 3.4) --------------------------------------------

    @Test
    void levelsWaitInTheOutboxWhileTheBackendRefusesThemAndOnlyTheNewestIsPushed() {
        backend.setLevelsDown(true);
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        stockService.adjust(new StockAdjustmentRequestDto(phone.id(), -4, "Damaged", UUID.randomUUID(), "admin"));
        awaitStockPush();

        assertThat(stockLevelOutbox.findAll()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getAttempts()).isPositive();
            assertThat(row.getLastError()).isEqualTo("503 Backend unavailable");
        });
        assertThat(getJson("/actuator/prometheus", null).body()).contains("stock_outbox_pending 2.0");
        assertThat(backend.stockOf(phone.id())).isEqualTo(10);

        backend.setLevelsDown(false);
        assertThat(stockLevelDispatcher.dispatchPending()).isEqualTo(1);

        assertThat(stockLevelOutbox.count()).isZero();
        assertThat(backend.levelPushes().getLast().levels()).containsExactly(new StockLevel(phone.id(), 6, 2));
        assertThat(backend.stockOf(phone.id())).isEqualTo(6);
        assertThat(getJson("/actuator/prometheus", null).body()).contains("stock_outbox_pending 0.0");
    }

    @Test
    void aPushCarriesTheCorrelationIdsOfTheChangeThatWroteIt() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        awaitStockPush();

        new com.awesome.warehouse.correlation.CorrelationContext("adjust-7", "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331").run(() -> stockService.adjust(new StockAdjustmentRequestDto(phone.id(), 1,
                "Found", UUID.randomUUID(), "admin")));
        awaitStockPush();

        assertThat(backend.levelPushCalls().getLast().requestId()).isEqualTo("adjust-7");
        assertThat(backend.levelPushCalls().getLast().traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    // --- reaper (contract 3.4, 4.1) ------------------------------------------------------------

    @Test
    void theReaperReleasesExpiredReservationsAndConfirmsThoseTheBackendSaved() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        UUID expired = reserve(phone, 2);
        UUID saved = reserve(phone, 3);
        UUID fresh = reserve(phone, 1);
        age(expired);
        age(saved);
        backend.saveOrderForReservation(saved, 77L);
        double reapedBefore = reaped();

        int released = reaper.reap();
        awaitStockPush();

        assertThat(released).isEqualTo(1);
        assertThat(reaped() - reapedBefore).isEqualTo(1.0);
        assertThat(stockReservations.findById(expired).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(stockReservations.findById(saved).orElseThrow()).satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.getOrderId()).isEqualTo(77L);
        });
        assertThat(stockReservations.findById(fresh).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
        assertThat(movementsOf(phone.id()).getLast()).satisfies(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.ORDER_RESTORED);
            assertThat(movement.getReservationId()).isEqualTo(expired);
            assertThat(movement.getActor()).isEqualTo("system");
            assertThat(movement.getReason()).isEqualTo("Reservation expired unconfirmed");
        });
        assertThat(movementsOf(phone.id())).filteredOn(m -> saved.equals(m.getReservationId()))
                .extracting(StockMovementEntity::getOrderId).containsExactly(77L);
        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(6);
        assertThat(backend.stockOf(phone.id())).isEqualTo(6);
        assertThat(reaper.reap()).as("nothing left to reap").isZero();
    }

    @Test
    void theReaperStopsWhileTheBackendCannotBeAsked() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        UUID expired = reserve(phone, 2);
        age(expired);
        backend.setAvailable(false);

        int released = reaper.reap();
        backend.setAvailable(true);

        assertThat(released).isZero();
        assertThat(stockReservations.findById(expired).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(8);
    }

    private UUID reserve(InternalProductDto product, int quantity) {
        UUID reservationId = UUID.randomUUID();
        stockService.reserve(new ReserveStockRequestDto(reservationId, "alice",
                List.of(new StockLineDto(product.id(), quantity))));
        return reservationId;
    }

    /** Moves a reservation past the confirm timeout (PT2M). */
    private void age(UUID reservationId) {
        jdbc.update("update warehouse.stock_reservations set created_at = ? where reservation_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(3), reservationId);
    }

    private double reaped() {
        return meterRegistry.get(StockMetrics.RESERVATIONS_REAPED).counter().count();
    }
}
