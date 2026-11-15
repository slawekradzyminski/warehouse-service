package com.awesome.warehouse.config;

import com.awesome.warehouse.client.EventFeedPage;
import com.awesome.warehouse.client.FeedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Native-only metadata that JVM tests cannot exercise; the native smoke test proves it end to end. */
class NativeRuntimeHintsTest {

    @Test
    void entityIdArraysAreRegisteredForHibernatesMultiIdLoader() {
        RuntimeHints hints = new RuntimeHints();
        new NativeRuntimeHints.Registrar().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(UUID[].class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(String[].class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("db/migration/V4__event_feed_consumer.sql"))
                .accepts(hints);
    }

    @Test
    void eventFeedTypesAreRegisteredForJsonBinding() {
        assertThat(NativeRuntimeHints.class.getAnnotation(RegisterReflectionForBinding.class).value())
                .contains(EventFeedPage.class, FeedEvent.class);
    }

    @Test
    void parcelAndLocationTypesAreRegisteredForJsonBinding() {
        assertThat(NativeRuntimeHints.class.getAnnotation(RegisterReflectionForBinding.class).value())
                .contains(com.awesome.warehouse.dto.fulfilment.ShipmentLineDto.class,
                        com.awesome.warehouse.dto.fulfilment.ReconciliationDto.Parcel.class,
                        com.awesome.warehouse.dto.fulfilment.WarehouseLocationDto.class);
        RuntimeHints hints = new RuntimeHints();
        new NativeRuntimeHints.Registrar().registerHints(hints, getClass().getClassLoader());
        assertThat(RuntimeHintsPredicates.resource()
                .forResource("db/migration/V6__warehouse_locations_and_parcels.sql")).accepts(hints);
    }

    @Test
    void stockLedgerTypesAreRegisteredForJsonBinding() {
        assertThat(NativeRuntimeHints.class.getAnnotation(RegisterReflectionForBinding.class).value()).contains(
                com.awesome.warehouse.client.StockLevel.class,
                com.awesome.warehouse.client.StockLevelsRequest.class,
                com.awesome.warehouse.client.StockLevelsResponse.class,
                com.awesome.warehouse.client.InventoryExportPage.class,
                com.awesome.warehouse.client.ExportedStockItem.class,
                com.awesome.warehouse.client.ExportedMovement.class,
                com.awesome.warehouse.client.OrderReference.class,
                com.awesome.warehouse.dto.stock.ReserveStockRequestDto.class,
                com.awesome.warehouse.dto.stock.StockLineDto.class,
                com.awesome.warehouse.dto.stock.ReservationDto.class,
                com.awesome.warehouse.dto.stock.ReservedLineDto.class,
                com.awesome.warehouse.dto.stock.ConfirmReservationRequestDto.class,
                com.awesome.warehouse.dto.stock.ReleasedReservationDto.class,
                com.awesome.warehouse.dto.stock.StockAdjustmentRequestDto.class,
                com.awesome.warehouse.dto.stock.StockAdjustmentDto.class,
                com.awesome.warehouse.dto.stock.InitialStockRequestDto.class,
                com.awesome.warehouse.dto.stock.StockItemDto.class,
                com.awesome.warehouse.dto.stock.InventoryMovementDto.class,
                com.awesome.warehouse.dto.stock.StockErrorDto.class);
        RuntimeHints hints = new RuntimeHints();
        new NativeRuntimeHints.Registrar().registerHints(hints, getClass().getClassLoader());
        assertThat(RuntimeHintsPredicates.resource().forResource("db/migration/V7__stock_ledger.sql")).accepts(hints);
    }
}
