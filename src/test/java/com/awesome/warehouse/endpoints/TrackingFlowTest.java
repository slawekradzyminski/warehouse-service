package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.tracking.PublicTrackingDto;
import com.awesome.warehouse.dto.tracking.TrackingDto;
import com.awesome.warehouse.dto.tracking.TrackingEventDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.fulfilment.TrackingStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.fulfilment.TrackingEventRepository;
import com.awesome.warehouse.service.fulfilment.ShipmentReconciler;
import com.awesome.warehouse.service.tracking.CarrierSimulator;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingFlowTest extends AbstractFlowTest {

    /** abs(hashCode) % 5 != 0: delivered on the first attempt (city index 1, Krakow). */
    private static final String DELIVERS_FIRST_TIME = "1234567890";
    /** abs(hashCode) % 5 == 0: the first delivery attempt fails (city index 5, Lodz). */
    private static final String FAILS_FIRST_ATTEMPT = "1234567894";
    private static final Duration STEP = Duration.ofMinutes(2);

    @Autowired private CarrierSimulator simulator;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private TrackingEventRepository eventRepository;
    @Autowired private ShipmentReconciler reconciler;

    private final LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).plusHours(1);

    @Test
    void aSimulatedShipmentIsAdvancedOneScanPerStepUntilDelivered() {
        ShipmentDto packed = packedShipment();

        ShipmentDto shipped = ship(packed.getId(), new ShipShipmentDto(Carrier.DHL, null));
        pinTrackingNumber(shipped.getId(), DELIVERS_FIRST_TIME);
        TrackingDto labelled = tracking(packed.getOrderId(), clientToken);
        int firstStep = simulator.advanceIdle(at(1));
        int repeatedStep = simulator.advanceIdle(at(1));
        int sooner = simulator.advanceIdle(at(1).plus(STEP).minusSeconds(1));
        simulator.advanceIdle(at(2));
        TrackingDto atDepot = tracking(packed.getOrderId(), clientToken);
        simulator.advanceIdle(at(3));
        OrderStatus whileOutForDelivery = backend.order(packed.getOrderId()).status();
        int delivering = simulator.advanceIdle(at(4));
        int afterDelivery = simulator.advanceIdle(at(9));
        TrackingDto delivered = tracking(packed.getOrderId(), clientToken);
        ShipmentEntity entity = shipmentRepository.findById(packed.getId()).orElseThrow();

        assertThat(Carrier.DHL.acceptsTrackingNumber(shipped.getTrackingNumber())).isTrue();
        assertThat(shipped.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(labelled.isSimulated()).isTrue();
        assertThat(labelled.getCurrentStatus()).isEqualTo(TrackingStatus.LABEL_CREATED);
        assertThat(labelled.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.getLocation()).isEqualTo("Awesome Testing Warehouse, Krakow");
            assertThat(e.getDescription()).isEqualTo("Shipping label created; the parcel is waiting for DHL pickup");
        });
        assertThat(labelled.getEstimatedDelivery())
                .isEqualTo(labelled.getEvents().getFirst().getOccurredAt().plus(STEP.multipliedBy(4)));
        assertThat(firstStep).isEqualTo(1);
        assertThat(repeatedStep).isZero();
        assertThat(sooner).isZero();
        assertThat(atDepot.getCurrentStatus()).isEqualTo(TrackingStatus.IN_TRANSIT);
        assertThat(atDepot.getEstimatedDelivery()).isEqualTo(at(2).plus(STEP.multipliedBy(2)));
        assertThat(atDepot.getEvents()).extracting(TrackingEventDto::getLocation)
                .containsExactly("Awesome Testing Warehouse, Krakow", "DHL Hub, Leipzig", "DHL depot, Krakow");
        assertThat(whileOutForDelivery).isEqualTo(OrderStatus.SHIPPED);
        assertThat(delivering).isEqualTo(1);
        assertThat(afterDelivery).isZero();
        assertThat(delivered.getCurrentStatus()).isEqualTo(TrackingStatus.DELIVERED);
        assertThat(delivered.getEvents()).extracting(TrackingEventDto::getStatus).containsExactly(
                TrackingStatus.LABEL_CREATED, TrackingStatus.IN_TRANSIT, TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY, TrackingStatus.DELIVERED);
        assertThat(delivered.getEvents()).extracting(TrackingEventDto::getOccurredAt).endsWith(at(3), at(4));
        assertThat(delivered.getEvents().getLast().getDescription()).isEqualTo("Delivered to the recipient");
        assertThat(delivered.getEstimatedDelivery()).isEqualTo(entity.getDeliveredAt());
        assertThat(entity.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(entity.getLastActor()).isEqualTo("carrier");
        assertThat(backend.order(packed.getOrderId()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(backend.order(packed.getOrderId()).fulfilmentState()).isEqualTo(FulfilmentState.DELIVERED);
        assertThat(sentNotifications()).extracting(NotificationRequest::template).containsExactly(
                NotificationTemplate.ORDER_SHIPPED, NotificationTemplate.SHIPMENT_OUT_FOR_DELIVERY,
                NotificationTemplate.ORDER_DELIVERED);
        assertThat(sentNotifications().get(1)).satisfies(n -> {
            assertThat(n.username()).isEqualTo("client");
            assertThat(n.properties()).isEqualTo(Map.of("orderId", String.valueOf(packed.getOrderId()),
                    "carrier", "DHL", "trackingNumber", DELIVERS_FIRST_TIME));
        });
    }

    @Test
    void aTrackingNumberMatchingTheFailureRuleFailsTheFirstDeliveryAttemptAndIsDeliveredOnTheNext() {
        ShipmentDto packed = packedShipment();
        ShipmentDto shipped = ship(packed.getId(), new ShipShipmentDto(Carrier.DHL, null));
        pinTrackingNumber(shipped.getId(), FAILS_FIRST_ATTEMPT);

        TrackingDto labelled = tracking(packed.getOrderId(), clientToken);
        for (int step = 1; step <= 4; step++) {
            simulator.advanceIdle(at(step));
        }
        TrackingDto failed = tracking(packed.getOrderId(), clientToken);
        ShipmentStatus afterFailure = shipmentRepository.findById(packed.getId()).orElseThrow().getStatus();
        simulator.advanceIdle(at(5));
        simulator.advanceIdle(at(6));
        TrackingDto delivered = tracking(packed.getOrderId(), clientToken);

        assertThat(labelled.getEstimatedDelivery())
                .isEqualTo(labelled.getEvents().getFirst().getOccurredAt().plus(STEP.multipliedBy(6)));
        assertThat(failed.getCurrentStatus()).isEqualTo(TrackingStatus.DELIVERY_FAILED);
        assertThat(failed.getEvents().getLast()).satisfies(e -> {
            assertThat(e.getLocation()).isEqualTo("Lodz");
            assertThat(e.getDescription()).startsWith("Delivery attempt failed");
        });
        assertThat(failed.getEstimatedDelivery()).isEqualTo(at(4).plus(STEP.multipliedBy(2)));
        assertThat(afterFailure).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(delivered.getEvents()).extracting(TrackingEventDto::getStatus).containsExactly(
                TrackingStatus.LABEL_CREATED, TrackingStatus.IN_TRANSIT, TrackingStatus.IN_TRANSIT,
                TrackingStatus.OUT_FOR_DELIVERY, TrackingStatus.DELIVERY_FAILED, TrackingStatus.OUT_FOR_DELIVERY,
                TrackingStatus.DELIVERED);
        assertThat(delivered.getEvents().get(5).getDescription()).isEqualTo("Out for delivery again (second attempt)");
        assertThat(backend.order(packed.getOrderId()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(sentNotifications()).extracting(NotificationRequest::template).containsExactly(
                NotificationTemplate.ORDER_SHIPPED, NotificationTemplate.SHIPMENT_OUT_FOR_DELIVERY,
                NotificationTemplate.SHIPMENT_DELIVERY_FAILED, NotificationTemplate.SHIPMENT_OUT_FOR_DELIVERY,
                NotificationTemplate.ORDER_DELIVERED);
    }

    @Test
    void operatorsCanFastForwardOnlySimulatedShippedShipments() {
        ShipmentDto simulated = ship(packedShipment().getId(), new ShipShipmentDto(Carrier.INPOST, null));
        pinTrackingNumber(simulated.getId(), "123456789012345678901234");
        ShipmentDto manual = ship(packedShipment().getId(), new ShipShipmentDto(Carrier.DHL, DELIVERS_FIRST_TIME));
        ShipmentDto packed = packedShipment();
        String advance = WAREHOUSE + "/shipments/" + simulated.getId() + "/carrier/advance";

        Response first = postJson(advance, null, warehouseToken);
        Response byAdmin = postJson(advance, null, adminToken);
        Response byClient = postJson(advance, null, clientToken);
        Response anonymous = postJson(advance, null, null);
        postJson(advance, null, warehouseToken);
        Response delivering = postJson(advance, null, warehouseToken);
        Response afterDelivery = postJson(advance, null, warehouseToken);
        Response onManual = postJson(WAREHOUSE + "/shipments/" + manual.getId() + "/carrier/advance", null, warehouseToken);
        Response onPacked = postJson(WAREHOUSE + "/shipments/" + packed.getId() + "/carrier/advance", null, warehouseToken);
        Response unknown = postJson(WAREHOUSE + "/shipments/999999/carrier/advance", null, warehouseToken);

        assertThat(Carrier.INPOST.acceptsTrackingNumber(simulated.getTrackingNumber())).isTrue();
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.as(TrackingDto.class).getCurrentStatus()).isEqualTo(TrackingStatus.IN_TRANSIT);
        assertThat(first.as(TrackingDto.class).getEvents().getLast().getLocation())
                .isEqualTo("InPost Sorting Centre, Pruszkow");
        assertThat(byAdmin.as(TrackingDto.class).getEvents().getLast().getLocation()).isEqualTo("InPost depot, Gdansk");
        assertThat(byClient.status()).isEqualTo(403);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(delivering.status()).isEqualTo(200);
        assertThat(delivering.as(TrackingDto.class).getCurrentStatus()).isEqualTo(TrackingStatus.DELIVERED);
        assertThat(delivering.as(TrackingDto.class).getEvents().getLast().getDescription())
                .isEqualTo("Delivered to the parcel locker");
        assertThat(shipmentRepository.findById(simulated.getId()).orElseThrow().getLastActor()).isEqualTo("warehouse");
        assertThat(afterDelivery.status()).isEqualTo(409);
        assertThat(afterDelivery.message()).isEqualTo("Only simulated SHIPPED shipments can be advanced");
        assertThat(onManual.status()).isEqualTo(409);
        assertThat(onPacked.status()).isEqualTo(409);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Shipment not found");
    }

    @Test
    void manualShipmentsKeepTheirTrackingNumberAndAreTrackedButNeverSimulated() {
        ShipmentDto packed = packedShipment();

        ShipmentDto shipped = ship(packed.getId(), new ShipShipmentDto(Carrier.UPS, " 1z999aa10123456784 "));
        TrackingDto inTransit = tracking(packed.getOrderId(), clientToken);
        int advanced = simulator.advanceIdle(at(30));
        Response delivered = postJson(WAREHOUSE + "/shipments/" + packed.getId() + "/deliver", null, warehouseToken);
        TrackingDto afterDelivery = tracking(packed.getOrderId(), adminToken);

        assertThat(shipped.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(inTransit.isSimulated()).isFalse();
        assertThat(inTransit.getCurrentStatus()).isEqualTo(TrackingStatus.LABEL_CREATED);
        assertThat(inTransit.getEstimatedDelivery()).isEqualTo(shipped.getShippedAt().plusDays(3));
        assertThat(advanced).isZero();
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.as(ShipmentDto.class).getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(afterDelivery.getEvents()).extracting(TrackingEventDto::getStatus)
                .containsExactly(TrackingStatus.LABEL_CREATED, TrackingStatus.DELIVERED);
        assertThat(afterDelivery.getEvents().getLast().getDescription()).isEqualTo("Delivery confirmed by the warehouse");
        assertThat(afterDelivery.getEstimatedDelivery()).isEqualTo(delivered.as(ShipmentDto.class).getDeliveredAt());
        assertThat(sentNotifications()).extracting(NotificationRequest::template)
                .containsExactly(NotificationTemplate.ORDER_SHIPPED, NotificationTemplate.ORDER_DELIVERED);
    }

    @Test
    void aBlankTrackingNumberIsRejectedWhileAnOmittedOneSelectsTheSimulatedCarrier() {
        ShipmentDto packed = packedShipment();

        Response blank = postJson(WAREHOUSE + "/shipments/" + packed.getId() + "/ship",
                new ShipShipmentDto(Carrier.UPS, "   "), warehouseToken);
        Response omitted = postJson(WAREHOUSE + "/shipments/" + packed.getId() + "/ship",
                Map.of("carrier", "UPS"), warehouseToken);

        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.body()).contains("Tracking number must not be blank");
        assertThat(omitted.status()).isEqualTo(200);
        assertThat(Carrier.UPS.acceptsTrackingNumber(omitted.as(ShipmentDto.class).getTrackingNumber())).isTrue();
    }

    @Test
    void theOwnerAndAdminsTrackAnOrderWhileOthersGet404() {
        ShipmentDto released = releaseToWarehouse(paidOrder().id());
        InternalOrderDto unreleased = paidOrder();

        Response beforeHandOver = getJson(ORDERS + "/" + released.getOrderId() + "/shipment/tracking", clientToken);
        Response byOther = getJson(ORDERS + "/" + released.getOrderId() + "/shipment/tracking", otherClientToken);
        Response byAdmin = getJson(ORDERS + "/" + released.getOrderId() + "/shipment/tracking", adminToken);
        Response anonymous = getJson(ORDERS + "/" + released.getOrderId() + "/shipment/tracking", null);
        Response notReleased = getJson(ORDERS + "/" + unreleased.id() + "/shipment/tracking", clientToken);

        assertThat(beforeHandOver.status()).isEqualTo(200);
        TrackingDto tracking = beforeHandOver.as(TrackingDto.class);
        assertThat(tracking.getShipmentId()).isEqualTo(released.getId());
        assertThat(tracking.getOrderId()).isEqualTo(released.getOrderId());
        assertThat(tracking.getEvents()).isEmpty();
        assertThat(tracking.getCurrentStatus()).isNull();
        assertThat(tracking.getEstimatedDelivery()).isNull();
        assertThat(tracking.getTrackingNumber()).isNull();
        assertThat(byOther.status()).isEqualTo(404);
        assertThat(byOther.message()).isEqualTo("Order not found");
        assertThat(byAdmin.status()).isEqualTo(200);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(notReleased.status()).isEqualTo(404);
        assertThat(notReleased.message()).isEqualTo("Shipment not found");
    }

    @Test
    void thePublicTrackingEndpointNeedsNoTokenAndHidesPrivateData() {
        ShipmentDto packed = packedShipment();
        ShipmentDto shipped = ship(packed.getId(), new ShipShipmentDto(Carrier.UPS, null));

        Response anonymous = getJson("/api/v1/tracking/" + shipped.getTrackingNumber().toLowerCase(), null);
        Response withToken = getJson("/api/v1/tracking/" + shipped.getTrackingNumber(), clientToken);
        Response unknown = getJson("/api/v1/tracking/1Z0000000000000000", null);
        JsonNode body = jsonMapper.readTree(anonymous.body());

        assertThat(anonymous.status()).isEqualTo(200);
        PublicTrackingDto tracking = anonymous.as(PublicTrackingDto.class);
        assertThat(tracking.getTrackingNumber()).isEqualTo(shipped.getTrackingNumber());
        assertThat(tracking.getCarrier()).isEqualTo(Carrier.UPS);
        assertThat(tracking.isSimulated()).isTrue();
        assertThat(tracking.getCurrentStatus()).isEqualTo(TrackingStatus.LABEL_CREATED);
        assertThat(tracking.getEstimatedDelivery()).isNotNull();
        assertThat(body.has("orderId")).isFalse();
        assertThat(body.has("username")).isFalse();
        assertThat(anonymous.body()).doesNotContain("client", "1 Main St", "30-001",
                "\"" + packed.getOrderId() + "\"");
        assertThat(withToken.status()).isEqualTo(200);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Tracking number not found");
    }

    @Test
    void aCustomerCancellationBeforeTheHandOverLeavesNothingToTrackOrSimulate() {
        ShipmentDto released = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(released.getOrderId());

        reconciler.reconcile();
        Response ship = postJson(WAREHOUSE + "/shipments/" + released.getId() + "/ship",
                new ShipShipmentDto(Carrier.DHL, null), warehouseToken);
        Response advance = postJson(WAREHOUSE + "/shipments/" + released.getId() + "/carrier/advance", null,
                warehouseToken);
        TrackingDto tracking = tracking(released.getOrderId(), clientToken);

        assertThat(ship.status()).isEqualTo(409);
        assertThat(ship.message()).isEqualTo("Cannot ship a shipment in status CANCELLED");
        assertThat(advance.status()).isEqualTo(409);
        assertThat(tracking.getEvents()).isEmpty();
        assertThat(tracking.getTrackingNumber()).isNull();
        assertThat(tracking.isSimulated()).isFalse();
        assertThat(simulator.advanceIdle(at(10))).isZero();
    }

    @Test
    void aRefusedHandOverRecordsNoScanAndNoSimulatedTrackingNumber() {
        ShipmentDto packed = packedShipment();
        backend.setRefuseFulfilment(true);

        Response refused = postJson(WAREHOUSE + "/shipments/" + packed.getId() + "/ship",
                new ShipShipmentDto(Carrier.DPD, null), warehouseToken);
        backend.setRefuseFulfilment(false);
        ShipmentEntity entity = shipmentRepository.findById(packed.getId()).orElseThrow();

        assertThat(refused.status()).isEqualTo(409);
        assertThat(entity.getStatus()).isEqualTo(ShipmentStatus.PACKED);
        assertThat(entity.isSimulated()).isFalse();
        assertThat(entity.getTrackingNumber()).isNull();
        assertThat(eventRepository.findByShipmentIdOrderByIdAsc(packed.getId())).isEmpty();
    }

    @Test
    void aBackendOutageAtDeliveryKeepsTheParcelOutForDeliveryAndEndsTheRun() {
        ShipmentDto first = ship(packedShipment().getId(), new ShipShipmentDto(Carrier.DHL, null));
        pinTrackingNumber(first.getId(), DELIVERS_FIRST_TIME);
        ShipmentDto second = ship(packedShipment().getId(), new ShipShipmentDto(Carrier.DHL, null));
        pinTrackingNumber(second.getId(), DELIVERS_FIRST_TIME.replace('0', '5'));
        for (int step = 1; step <= 3; step++) {
            simulator.advanceIdle(at(step));
        }
        backend.setAvailable(false);

        int duringOutage = simulator.advanceIdle(at(4));
        backend.setAvailable(true);
        TrackingStatus firstAfterOutage = lastStatus(first.getId());
        TrackingStatus secondAfterOutage = lastStatus(second.getId());
        int afterOutage = simulator.advanceIdle(at(4));

        assertThat(duringOutage).isZero();
        assertThat(firstAfterOutage).isEqualTo(TrackingStatus.OUT_FOR_DELIVERY);
        assertThat(secondAfterOutage).isEqualTo(TrackingStatus.OUT_FOR_DELIVERY);
        assertThat(afterOutage).isEqualTo(2);
        assertThat(shipmentRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void theTrackingPathsAreDocumented() {
        JsonNode paths = jsonMapper.readTree(getJson("/v3/api-docs", null).body()).get("paths");

        assertThat(paths.propertyNames()).contains("/api/v1/orders/{id}/shipment/tracking",
                "/api/v1/tracking/{trackingNumber}", "/api/v1/warehouse/shipments/{id}/carrier/advance");
        assertThat(paths.at("/~1api~1v1~1tracking~1{trackingNumber}/get").has("security")).isFalse();
    }

    private TrackingStatus lastStatus(Long shipmentId) {
        return eventRepository.findByShipmentIdOrderByIdAsc(shipmentId).getLast().getStatus();
    }

    private LocalDateTime at(int step) {
        return start.plusMinutes(10L * step);
    }

    private void pinTrackingNumber(Long shipmentId, String trackingNumber) {
        ShipmentEntity shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        shipment.setTrackingNumber(trackingNumber);
        shipmentRepository.save(shipment);
    }

    private TrackingDto tracking(Long orderId, String token) {
        Response response = getJson(ORDERS + "/" + orderId + "/shipment/tracking", token);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.as(TrackingDto.class);
    }

    private ShipmentDto ship(Long shipmentId, ShipShipmentDto request) {
        Response response = postJson(WAREHOUSE + "/shipments/" + shipmentId + "/ship", request, warehouseToken);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.as(ShipmentDto.class);
    }

    private ShipmentDto packedShipment() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        assertThat(postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pick", null, warehouseToken).status())
                .isEqualTo(200);
        assertThat(postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pack",
                new PackShipmentDto(new BigDecimal("1.20")), warehouseToken).status()).isEqualTo(200);
        return shipment;
    }

    private InternalOrderDto paidOrder() {
        return backend.order("client", OrderStatus.PAID, null, backend.product("Phone", "Electronics", "25.00", 10), 1);
    }

    private ShipmentDto releaseToWarehouse(Long orderId) {
        Response response = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(orderId), warehouseToken);
        assertThat(response.status()).isEqualTo(201);
        return response.as(ShipmentDto.class);
    }
}
