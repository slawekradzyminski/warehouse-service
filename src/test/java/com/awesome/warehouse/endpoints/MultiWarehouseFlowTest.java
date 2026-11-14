package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.fulfilment.ReallocateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ReconciliationDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentLineDto;
import com.awesome.warehouse.dto.fulfilment.ShortPickDto;
import com.awesome.warehouse.dto.fulfilment.UpdateLocationDto;
import com.awesome.warehouse.dto.fulfilment.WarehouseLocationDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.tracking.TrackingDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.fulfilment.StockingRole;
import com.awesome.warehouse.entity.returns.OrderLockEntity;
import com.awesome.warehouse.repository.fulfilment.ShipmentLineRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.returns.OrderLockRepository;
import com.awesome.warehouse.service.event.EventFeedPoller;
import com.awesome.warehouse.service.fulfilment.ShipmentReconciler;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Warehouse locations and split shipments end to end (contract section 3). */
class MultiWarehouseFlowTest extends AbstractFlowTest {

    private static final String LOCATIONS = WAREHOUSE + "/locations";

    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private ShipmentLineRepository lineRepository;
    @Autowired private OrderLockRepository orderLockRepository;
    @Autowired private EventFeedPoller poller;
    @Autowired private ShipmentReconciler reconciler;

    private InternalProductDto phone;
    private InternalProductDto cable;
    private InternalProductDto novel;

    @BeforeEach
    void catalogue() {
        useDemoSplit();
        phone = backend.product("Phone", "Electronics", "25.00", 50);
        cable = backend.product("Cable", "Electronics", "5.00", 50);
        novel = backend.product("Novel", "Books", "10.00", 50);
    }

    // --- release and the customer views ---

    @Test
    void aMixedCartIsReleasedAsOneParcelPerLocationWithItsLines() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 2, novel, 1, phone, 1);

        Response created = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        List<ShipmentDto> parcels = getJson(ORDERS + "/" + order.id() + "/shipments", clientToken)
                .as(new TypeReference<List<ShipmentDto>>() {});
        ShipmentDto first = getJson(ORDERS + "/" + order.id() + "/shipment", clientToken).as(ShipmentDto.class);
        PickListDto gdanskPick = getJson(WAREHOUSE + "/shipments/" + parcels.get(1).getId() + "/pick-list",
                warehouseToken).as(PickListDto.class);

        assertThat(created.status()).isEqualTo(201);
        ShipmentDto createdParcel = created.as(ShipmentDto.class);
        assertThat(createdParcel.getLocationCode()).isEqualTo("KRK");
        assertThat(createdParcel.getParcelCount()).isEqualTo(2);
        assertThat(parcels).extracting(ShipmentDto::getLocationCode, ShipmentDto::getLocationName,
                        ShipmentDto::getParcelIndex, ShipmentDto::getParcelCount, ShipmentDto::getTotalUnits)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("KRK", "Krakow Central", 1, 2, 3),
                        org.assertj.core.groups.Tuple.tuple("GDN", "Gdansk North", 2, 2, 1));
        assertThat(parcels.get(0).getLines()).extracting(ShipmentLineDto::getProductId, ShipmentLineDto::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(phone.id(), 3));
        assertThat(parcels.get(1).getLines()).singleElement().satisfies(line -> {
            assertThat(line.getProductName()).isEqualTo("Novel");
            assertThat(line.getCategory()).isEqualTo("Books");
            assertThat(line.getQuantity()).isEqualTo(1);
        });
        assertThat(first.getId()).isEqualTo(createdParcel.getId());
        assertThat(first.getParcelCount()).isEqualTo(2);
        assertThat(gdanskPick.getItems()).extracting(i -> i.getProductName()).containsExactly("Novel");
        assertThat(gdanskPick.getTotalUnits()).isEqualTo(1);
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.AWAITING_PICK);
        assertThat(orderLockRepository.findById(order.id())).get()
                .extracting(OrderLockEntity::getMirroredState).isEqualTo(FulfilmentState.AWAITING_PICK);
    }

    @Test
    void anOrderNoActiveLocationCanShipIsRefusedAndNothingIsCreated() {
        InternalProductDto kite = backend.product("Kite", "Kites", "9.00", 5);
        stockingRepository.deleteAll();
        stock("KRK", "Electronics", StockingRole.PRIMARY);
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, kite, 1);

        Response refused = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        setActive("KRK", false);
        Response noneActive = postJson(WAREHOUSE + "/shipments",
                new CreateShipmentDto(backend.order("client", OrderStatus.PAID, null, phone, 1).id()), warehouseToken);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.message()).isEqualTo("No active warehouse location stocks Kites");
        assertThat(noneActive.status()).isEqualTo(409);
        assertThat(noneActive.message()).isEqualTo("No active warehouse location stocks Electronics");
        assertThat(shipmentRepository.count()).isZero();
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.NONE);
        assertThat(backend.calls()).noneMatch(call -> call.startsWith("PUT fulfilment"));
    }

    @Test
    void anInactiveLocationIsSkippedAtRelease() {
        setActive("GDN", false);
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);

        ShipmentDto created = release(order);

        assertThat(created.getParcelCount()).isEqualTo(1);
        assertThat(created.getLocationCode()).isEqualTo("KRK");
        assertThat(created.getLines()).hasSize(2);
    }

    @Test
    void theParcelListIsOwnedByTheCustomerAndEmptyBeforeRelease() {
        InternalOrderDto released = backend.order("client", OrderStatus.PAID, null, phone, 1);
        release(released);
        InternalOrderDto unreleased = backend.order("client", OrderStatus.PAID, null, phone, 1);

        assertThat(getJson(ORDERS + "/" + unreleased.id() + "/shipments", clientToken).body()).isEqualTo("[]");
        assertThat(getJson(ORDERS + "/" + released.id() + "/shipments", adminToken).status()).isEqualTo(200);
        assertThat(getJson(ORDERS + "/" + released.id() + "/shipments", otherClientToken).status()).isEqualTo(404);
        assertThat(getJson(ORDERS + "/" + released.id() + "/shipments", otherClientToken).message())
                .isEqualTo("Order not found");
        assertThat(getJson(ORDERS + "/" + unreleased.id() + "/shipments", otherClientToken).status()).isEqualTo(404);
        assertThat(getJson(ORDERS + "/999999/shipments", clientToken).status()).isEqualTo(404);
        assertThat(getJson(ORDERS + "/" + released.id() + "/shipments", null).status()).isEqualTo(401);
        backend.setAvailable(false);
        Response down = getJson(ORDERS + "/" + unreleased.id() + "/shipments", clientToken);
        Response releasedWhileDown = getJson(ORDERS + "/" + released.id() + "/shipments", clientToken);
        backend.setAvailable(true);
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.message()).isEqualTo("Backend unavailable");
        assertThat(releasedWhileDown.status()).isEqualTo(200);
    }

    // --- per-parcel state machine and the aggregate mirror ---

    @Test
    void parcelsMoveIndependentlyWhileTheBackendSeesTheForwardOnlyAggregate() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        List<ShipmentDto> parcels = releaseAll(order);
        Long krakow = parcels.get(0).getId();
        Long gdansk = parcels.get(1).getId();

        transition(krakow, "pick", null);
        FulfilmentState afterFirstPick = backend.order(order.id()).fulfilmentState();
        transition(krakow, "pack", new PackShipmentDto(BigDecimal.ONE));
        transition(krakow, "ship", new ShipShipmentDto(Carrier.DHL, "1234567890"));
        FulfilmentState afterFirstShip = backend.order(order.id()).fulfilmentState();
        Response cancelWhileShipped = postJson(WAREHOUSE + "/shipments/" + gdansk + "/cancel", null, warehouseToken);
        transition(gdansk, "pick", null);
        transition(gdansk, "pack", new PackShipmentDto(BigDecimal.ONE));
        FulfilmentState bothPacked = backend.order(order.id()).fulfilmentState();
        transition(gdansk, "ship", new ShipShipmentDto(Carrier.DPD, "12345678901234"));
        FulfilmentState bothShipped = backend.order(order.id()).fulfilmentState();
        transition(krakow, "deliver", null);
        OrderStatus afterFirstDelivery = backend.order(order.id()).status();
        transition(gdansk, "deliver", null);

        assertThat(afterFirstPick).isEqualTo(FulfilmentState.PICKED);
        assertThat(afterFirstShip).isEqualTo(FulfilmentState.PICKED);
        assertThat(cancelWhileShipped.status()).isEqualTo(409);
        assertThat(cancelWhileShipped.message()).isEqualTo("Cannot cancel order " + order.id()
                + ": its parcel from KRK is already SHIPPED");
        assertThat(bothPacked).isEqualTo(FulfilmentState.PACKED);
        assertThat(bothShipped).isEqualTo(FulfilmentState.SHIPPED);
        assertThat(afterFirstDelivery).isEqualTo(OrderStatus.SHIPPED);
        assertThat(backend.order(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(backend.calls()).filteredOn(call -> call.startsWith("PUT fulfilment"))
                .containsExactly("PUT fulfilment " + order.id() + " AWAITING_PICK",
                        "PUT fulfilment " + order.id() + " PICKED",
                        "PUT fulfilment " + order.id() + " PACKED",
                        "PUT fulfilment " + order.id() + " SHIPPED",
                        "PUT fulfilment " + order.id() + " DELIVERED");
        assertThat(sentNotifications()).extracting(NotificationRequest::template).containsExactly(
                NotificationTemplate.ORDER_SHIPPED, NotificationTemplate.ORDER_SHIPPED,
                NotificationTemplate.ORDER_DELIVERED);
        assertThat(sentNotifications()).extracting(n -> n.properties().get("trackingNumber"))
                .containsExactly("1234567890", "12345678901234", null);
    }

    @Test
    void cancellingOneParcelCancelsTheOrderAndEveryUnpackedParcel() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 2, novel, 1);
        List<ShipmentDto> parcels = releaseAll(order);
        transition(parcels.get(1).getId(), "pick", null);

        ShipmentDto cancelled = transition(parcels.get(0).getId(), "cancel", null).as(ShipmentDto.class);

        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipmentRepository.findByOrderIdOrderByIdAsc(order.id()))
                .extracting(ShipmentEntity::getStatus).containsOnly(ShipmentStatus.CANCELLED);
        assertThat(backend.order(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(backend.stockOf(phone.id())).isEqualTo(52);
    }

    @Test
    void aCustomerCancellationFromTheFeedCancelsEveryParcel() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        releaseAll(order);
        backend.cancelByCustomer(order.id());

        poller.poll();

        assertThat(shipmentRepository.findByOrderIdOrderByIdAsc(order.id())).allSatisfy(parcel -> {
            assertThat(parcel.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(parcel.getLastActor()).isEqualTo("system");
        });
    }

    @Test
    void theSimulatedCarrierDeliversEachParcelAndTheOrderOnlyWithTheLast() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        List<ShipmentDto> parcels = releaseAll(order);
        for (ShipmentDto parcel : parcels) {
            transition(parcel.getId(), "pick", null);
            transition(parcel.getId(), "pack", new PackShipmentDto(BigDecimal.ONE));
            transition(parcel.getId(), "ship", Map.of("carrier", "DHL"));
        }

        TrackingDto krakow = advanceUntilDelivered(parcels.get(0).getId());
        OrderStatus afterFirst = backend.order(order.id()).status();
        advanceUntilDelivered(parcels.get(1).getId());

        assertThat(krakow.getEvents().getLast().getStatus().name()).isEqualTo("DELIVERED");
        assertThat(afterFirst).isEqualTo(OrderStatus.SHIPPED);
        assertThat(backend.order(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(sentNotifications()).filteredOn(n -> n.template() == NotificationTemplate.ORDER_DELIVERED).hasSize(1);
    }

    @Test
    void returnsStayPerOrderAfterEveryParcelIsDelivered() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        for (ShipmentDto parcel : releaseAll(order)) {
            transition(parcel.getId(), "pick", null);
            transition(parcel.getId(), "pack", new PackShipmentDto(BigDecimal.ONE));
            transition(parcel.getId(), "ship", new ShipShipmentDto(Carrier.DHL, "1234567890"));
            transition(parcel.getId(), "deliver", null);
        }

        Response returned = postJson(ORDERS + "/" + order.id() + "/returns", Map.of("reason", "NO_LONGER_NEEDED",
                "items", List.of(Map.of("productId", phone.id(), "quantity", 1),
                        Map.of("productId", novel.id(), "quantity", 1))), clientToken);

        assertThat(returned.status()).as(returned.body()).isEqualTo(201);
    }

    // --- pre-1.5.0 shipments ---

    @Test
    void aShipmentCreatedBeforeTheMigrationGetsItsLinesOnFirstReadAndKeepsWorking() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 2, novel, 1);
        backend.updateFulfilment(order.id(), FulfilmentState.AWAITING_PICK);
        ShipmentEntity legacy = shipmentRepository.saveAndFlush(ShipmentEntity.builder().orderId(order.id())
                .username("client").locationCode("KRK").totalUnits(3).status(ShipmentStatus.AWAITING_PICK)
                .createdBy("warehouse").lastActor("warehouse").linesPending(true).build());

        backend.setAvailable(false);
        ShipmentDto whileDown = getJson(WAREHOUSE + "/shipments/" + legacy.getId(), warehouseToken).as(ShipmentDto.class);
        backend.setAvailable(true);
        ShipmentDto read = getJson(WAREHOUSE + "/shipments/" + legacy.getId(), warehouseToken).as(ShipmentDto.class);
        ShipmentDto picked = transition(legacy.getId(), "pick", null).as(ShipmentDto.class);

        assertThat(whileDown.getLines()).isEmpty();
        assertThat(read.getLines()).extracting(ShipmentLineDto::getProductName, ShipmentLineDto::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Phone", 2),
                        org.assertj.core.groups.Tuple.tuple("Novel", 1));
        assertThat(read.getUpdatedAt()).isEqualTo(whileDown.getUpdatedAt());
        assertThat(shipmentRepository.findById(legacy.getId())).get().extracting(ShipmentEntity::isLinesPending)
                .isEqualTo(false);
        assertThat(picked.getStatus()).isEqualTo(ShipmentStatus.PICKED);
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.PICKED);
        assertThat(lineRepository.findByShipmentIdOrderByIdAsc(legacy.getId())).hasSize(2);
    }

    @Test
    void theReconcilerFillsPendingLinesInBatches() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1);
        ShipmentEntity legacy = shipmentRepository.saveAndFlush(ShipmentEntity.builder().orderId(order.id())
                .username("client").locationCode("KRK").totalUnits(1).status(ShipmentStatus.DELIVERED)
                .createdBy("warehouse").lastActor("warehouse").linesPending(true).build());

        reconciler.reconcile();

        assertThat(lineRepository.findByShipmentIdOrderByIdAsc(legacy.getId())).singleElement()
                .satisfies(line -> assertThat(line.getProductId()).isEqualTo(phone.id()));
        assertThat(shipmentRepository.findById(legacy.getId()).orElseThrow().isLinesPending()).isFalse();
    }

    // --- locations ---

    @Test
    void locationsAreListedWithTheirStockingAndCanBeDeactivated() {
        List<WarehouseLocationDto> locations = getJson(LOCATIONS, warehouseToken)
                .as(new TypeReference<List<WarehouseLocationDto>>() {});
        Response deactivated = putJson(LOCATIONS + "/gdn", new UpdateLocationDto(false), adminToken);
        Response missingFlag = putJson(LOCATIONS + "/GDN", Map.of(), warehouseToken);
        Response unknown = putJson(LOCATIONS + "/WAW", new UpdateLocationDto(true), warehouseToken);

        assertThat(locations).extracting(WarehouseLocationDto::getCode).containsExactly("KRK", "GDN");
        assertThat(locations.get(0).getName()).isEqualTo("Krakow Central");
        assertThat(locations.get(0).getCity()).isEqualTo("Krakow");
        assertThat(locations.get(0).getPriority()).isEqualTo(1);
        assertThat(locations.get(0).getPrimaryCategories()).containsExactly("*");
        assertThat(locations.get(0).getBackupCategories()).containsExactly("Books");
        assertThat(locations.get(1).getPrimaryCategories()).containsExactly("Books");
        assertThat(locations.get(1).getBackupCategories()).containsExactly("*");
        assertThat(deactivated.status()).isEqualTo(200);
        assertThat(deactivated.as(WarehouseLocationDto.class).isActive()).isFalse();
        assertThat(locationRepository.findById("GDN").orElseThrow().isActive()).isFalse();
        assertThat(missingFlag.status()).isEqualTo(400);
        assertThat(missingFlag.body()).contains("active is required");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Warehouse location not found");
        assertThat(getJson(LOCATIONS, clientToken).status()).isEqualTo(403);
        assertThat(getJson(LOCATIONS, null).status()).isEqualTo(401);
        assertThat(putJson(LOCATIONS + "/GDN", new UpdateLocationDto(true), clientToken).status()).isEqualTo(403);
        assertThat(putJson(LOCATIONS + "/GDN", new UpdateLocationDto(true), null).status()).isEqualTo(401);
    }

    // --- reallocation ---

    @Test
    void reallocatingWithoutATargetMergesIntoTheOrdersParcelAtTheBackupLocation() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 2);
        List<ShipmentDto> parcels = releaseAll(order);

        Response moved = postJson(WAREHOUSE + "/shipments/" + parcels.get(1).getId() + "/reallocate", null,
                warehouseToken);

        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        ShipmentDto merged = moved.as(ShipmentDto.class);
        assertThat(merged.getId()).isEqualTo(parcels.get(0).getId());
        assertThat(merged.getLocationCode()).isEqualTo("KRK");
        assertThat(merged.getParcelCount()).isEqualTo(1);
        assertThat(merged.getTotalUnits()).isEqualTo(3);
        assertThat(merged.getLines()).extracting(ShipmentLineDto::getProductId, ShipmentLineDto::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(phone.id(), 1),
                        org.assertj.core.groups.Tuple.tuple(novel.id(), 2));
        assertThat(merged.getLastActor()).isEqualTo("warehouse");
        assertThat(shipmentRepository.findById(parcels.get(1).getId())).isEmpty();
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.AWAITING_PICK);
    }

    @Test
    void reallocatingToALocationWithoutAParcelMovesTheParcelItself() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1);
        ShipmentDto parcel = release(order);

        ShipmentDto moved = postJson(WAREHOUSE + "/shipments/" + parcel.getId() + "/reallocate",
                new ReallocateShipmentDto(" gdn "), adminToken).as(ShipmentDto.class);

        assertThat(moved.getId()).isEqualTo(parcel.getId());
        assertThat(moved.getLocationCode()).isEqualTo("GDN");
        assertThat(moved.getLocationName()).isEqualTo("Gdansk North");
        assertThat(moved.getLines()).hasSize(1);
    }

    @Test
    void reallocationIsRefusedWithAReasonForEveryInvalidCase() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        List<ShipmentDto> parcels = releaseAll(order);
        Long krakow = parcels.get(0).getId();
        Long gdansk = parcels.get(1).getId();
        transition(krakow, "pick", null);

        Response picked = reallocate(krakow, null);
        Response invalidCode = reallocate(gdansk, "not a code");
        Response unknownShipment = reallocate(999_999L, null);
        Response unknownLocation = reallocate(gdansk, "WAW");
        Response same = reallocate(gdansk, "GDN");
        Response pickedTarget = reallocate(gdansk, "KRK");
        Response noAlternative = reallocate(gdansk, null);
        InternalOrderDto single = backend.order("client", OrderStatus.PAID, null, novel, 1);
        Long singleParcel = release(single).getId();
        setActive("KRK", false);
        Response inactive = reallocate(singleParcel, "KRK");
        setActive("KRK", true);
        stockingRepository.deleteAll();
        stock("GDN", "Books", StockingRole.PRIMARY);
        stock("KRK", "Electronics", StockingRole.PRIMARY);
        Response notStocked = reallocate(singleParcel, "KRK");

        assertThat(picked.status()).isEqualTo(409);
        assertThat(picked.message()).isEqualTo("Cannot reallocate a shipment in status PICKED");
        assertThat(invalidCode.status()).isEqualTo(400);
        assertThat(invalidCode.body()).contains("Target location must be a location code");
        assertThat(unknownShipment.status()).isEqualTo(404);
        assertThat(unknownShipment.message()).isEqualTo("Shipment not found");
        assertThat(unknownLocation.status()).isEqualTo(404);
        assertThat(unknownLocation.message()).isEqualTo("Warehouse location not found");
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.message()).isEqualTo("Shipment is already at GDN");
        assertThat(pickedTarget.status()).isEqualTo(409);
        assertThat(pickedTarget.message()).isEqualTo("The order's parcel at KRK is already PICKED");
        assertThat(noAlternative.status()).isEqualTo(409);
        assertThat(noAlternative.message()).isEqualTo("No other active warehouse location can take this parcel");
        assertThat(inactive.status()).isEqualTo(409);
        assertThat(inactive.message()).isEqualTo("Warehouse location KRK is not active");
        assertThat(notStocked.status()).isEqualTo(409);
        assertThat(notStocked.message()).isEqualTo("Warehouse location KRK does not stock Books");
        assertThat(reallocate(gdansk, null, clientToken).status()).isEqualTo(403);
        assertThat(reallocate(gdansk, null, null).status()).isEqualTo(401);
        assertThat(shipmentRepository.findByOrderIdOrderByIdAsc(order.id())).hasSize(2);
    }

    // --- short picks ---

    @Test
    void aShortPickMovesTheMissingUnitsToANewParcelAtAnotherLocation() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 3, cable, 1);
        ShipmentDto krakow = release(order);

        Response moved = shortPick(krakow.getId(), phone.id(), 2, warehouseToken);

        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        ShipmentDto gdansk = moved.as(ShipmentDto.class);
        assertThat(gdansk.getLocationCode()).isEqualTo("GDN");
        assertThat(gdansk.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(gdansk.getParcelIndex()).isEqualTo(2);
        assertThat(gdansk.getParcelCount()).isEqualTo(2);
        assertThat(gdansk.getTotalUnits()).isEqualTo(2);
        assertThat(gdansk.getLines()).extracting(ShipmentLineDto::getProductId, ShipmentLineDto::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(phone.id(), 2));
        ShipmentDto rest = getJson(WAREHOUSE + "/shipments/" + krakow.getId(), warehouseToken).as(ShipmentDto.class);
        assertThat(rest.getTotalUnits()).isEqualTo(2);
        assertThat(rest.getLines()).extracting(ShipmentLineDto::getProductId, ShipmentLineDto::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(phone.id(), 1),
                        org.assertj.core.groups.Tuple.tuple(cable.id(), 1));
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.AWAITING_PICK);
    }

    @Test
    void aShortPickOfAWholeParcelMergesIntoTheExistingParcelAndRemovesTheEmptyOne() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        List<ShipmentDto> parcels = releaseAll(order);

        ShipmentDto merged = shortPick(parcels.get(1).getId(), novel.id(), 1, warehouseToken).as(ShipmentDto.class);

        assertThat(merged.getId()).isEqualTo(parcels.get(0).getId());
        assertThat(merged.getParcelCount()).isEqualTo(1);
        assertThat(merged.getTotalUnits()).isEqualTo(2);
        assertThat(merged.getLines()).extracting(ShipmentLineDto::getProductId).containsExactly(phone.id(), novel.id());
        assertThat(shipmentRepository.findById(parcels.get(1).getId())).isEmpty();
    }

    @Test
    void shortPicksAreRefusedWithAReasonForEveryInvalidCase() {
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 2, novel, 1);
        List<ShipmentDto> parcels = releaseAll(order);
        Long krakow = parcels.get(0).getId();
        Long gdansk = parcels.get(1).getId();
        transition(gdansk, "pick", null);

        Response missingFields = postJson(WAREHOUSE + "/shipments/" + krakow + "/short-pick", Map.of(), warehouseToken);
        Response zero = shortPick(krakow, phone.id(), 0, warehouseToken);
        Response unknownShipment = shortPick(999_999L, phone.id(), 1, warehouseToken);
        Response unknownProduct = shortPick(krakow, novel.id(), 1, warehouseToken);
        Response tooMany = shortPick(krakow, phone.id(), 3, warehouseToken);
        Response picked = shortPick(gdansk, novel.id(), 1, warehouseToken);
        Response targetPicked = shortPick(krakow, phone.id(), 1, warehouseToken);

        assertThat(missingFields.status()).isEqualTo(400);
        assertThat(missingFields.body()).contains("Product ID is required").contains("Missing quantity is required");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.body()).contains("Missing quantity must be at least 1");
        assertThat(unknownShipment.status()).isEqualTo(404);
        assertThat(unknownShipment.message()).isEqualTo("Shipment not found");
        assertThat(unknownProduct.status()).isEqualTo(404);
        assertThat(unknownProduct.message()).isEqualTo("Product is not in this shipment");
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.message()).isEqualTo("Missing quantity exceeds the 2 units of the product in this shipment");
        assertThat(picked.status()).isEqualTo(409);
        assertThat(picked.message()).isEqualTo("Cannot short-pick a shipment in status PICKED");
        assertThat(targetPicked.status()).isEqualTo(409);
        assertThat(targetPicked.message()).isEqualTo("No other active warehouse location stocks Electronics");
        assertThat(shortPick(krakow, phone.id(), 1, clientToken).status()).isEqualTo(403);
        assertThat(shortPick(krakow, phone.id(), 1, null).status()).isEqualTo(401);
        assertThat(lineRepository.findByShipmentIdOrderByIdAsc(krakow)).singleElement()
                .satisfies(line -> assertThat(line.getQuantity()).isEqualTo(2));
    }

    // --- reconciliation ---

    @Test
    void theReconciliationReportListsOrdersWhoseParcelsDisagreeWithTheBackend() {
        InternalOrderDto agreeing = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        releaseAll(agreeing);
        InternalOrderDto cancelled = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
        List<ShipmentDto> cancelledParcels = releaseAll(cancelled);
        assertThat(getJson(WAREHOUSE + "/reconciliation", warehouseToken).body()).isEqualTo("[]");
        backend.cancelByCustomer(cancelled.id());

        List<ReconciliationDto> report = getJson(WAREHOUSE + "/reconciliation", adminToken)
                .as(new TypeReference<List<ReconciliationDto>>() {});
        backend.setAvailable(false);
        Response down = getJson(WAREHOUSE + "/reconciliation", warehouseToken);
        backend.setAvailable(true);

        assertThat(report).singleElement().satisfies(entry -> {
            assertThat(entry.getOrderId()).isEqualTo(cancelled.id());
            assertThat(entry.getBackendState()).isEqualTo(FulfilmentState.CANCELLED);
            assertThat(entry.getAggregateState()).isEqualTo(FulfilmentState.AWAITING_PICK);
            assertThat(entry.getLastMirroredState()).isEqualTo(FulfilmentState.AWAITING_PICK);
            assertThat(entry.getParcels()).extracting(ReconciliationDto.Parcel::getShipmentId)
                    .containsExactly(cancelledParcels.get(0).getId(), cancelledParcels.get(1).getId());
            assertThat(entry.getParcels()).extracting(ReconciliationDto.Parcel::getLocationCode)
                    .containsExactly("KRK", "GDN");
            assertThat(entry.getParcels()).extracting(ReconciliationDto.Parcel::getStatus)
                    .containsOnly(ShipmentStatus.AWAITING_PICK);
        });
        assertThat(down.status()).isEqualTo(503);
        assertThat(getJson(WAREHOUSE + "/reconciliation", clientToken).status()).isEqualTo(403);
        assertThat(getJson(WAREHOUSE + "/reconciliation", null).status()).isEqualTo(401);
    }

    // --- helpers ---

    private ShipmentDto release(InternalOrderDto order) {
        Response response = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.as(ShipmentDto.class);
    }

    private List<ShipmentDto> releaseAll(InternalOrderDto order) {
        release(order);
        return getJson(ORDERS + "/" + order.id() + "/shipments", adminToken).as(new TypeReference<List<ShipmentDto>>() {});
    }

    private Response transition(Long shipmentId, String action, Object body) {
        Response response = postJson(WAREHOUSE + "/shipments/" + shipmentId + "/" + action, body, warehouseToken);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response;
    }

    private Response reallocate(Long shipmentId, String target) {
        return reallocate(shipmentId, target, warehouseToken);
    }

    private Response reallocate(Long shipmentId, String target, String token) {
        return postJson(WAREHOUSE + "/shipments/" + shipmentId + "/reallocate",
                target == null ? null : new ReallocateShipmentDto(target), token);
    }

    private Response shortPick(Long shipmentId, Long productId, int quantity, String token) {
        return postJson(WAREHOUSE + "/shipments/" + shipmentId + "/short-pick", new ShortPickDto(productId, quantity), token);
    }

    private TrackingDto advanceUntilDelivered(Long shipmentId) {
        TrackingDto tracking = null;
        for (int step = 0; step < 10; step++) {
            tracking = transition(shipmentId, "carrier/advance", null).as(TrackingDto.class);
            if (tracking.getEvents().getLast().getStatus().name().equals("DELIVERED")) {
                return tracking;
            }
        }
        throw new AssertionError("Parcel " + shipmentId + " was not delivered: " + tracking);
    }
}
