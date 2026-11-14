package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.repository.event.EventCursorRepository;
import com.awesome.warehouse.repository.event.ProcessedEventRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.service.event.EventFeedPoller;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** order.cancelled events from the backend feed cancel the shipment without a call back to the backend. */
class EventFeedFlowTest extends AbstractFlowTest {

    @Autowired private EventFeedPoller poller;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private EventCursorRepository cursors;
    @Autowired private ProcessedEventRepository processedEvents;

    @Test
    void aCustomerCancellationArrivesThroughTheFeedAndCancelsTheShipment() {
        ShipmentDto awaiting = releaseToWarehouse(paidOrder().id());
        ShipmentDto other = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(awaiting.getOrderId());
        int callsBefore = backend.calls().size();

        int handled = poller.poll();

        assertThat(handled).isEqualTo(1);
        ShipmentEntity cancelled = shipment(awaiting.getId());
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getLastActor()).isEqualTo("system");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(shipment(other.getId()).getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(backend.calls().subList(callsBefore, backend.calls().size()))
                .as("the order is already cancelled: only the feed is read")
                .containsExactly("GET events after 0");
        assertThat(poller.cursor()).isEqualTo(backend.feed().getLast().sequence());
        assertThat(processedEvents.existsById(backend.feed().getLast().id())).isTrue();
    }

    @Test
    void aPickedShipmentIsCancelledToo() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        transition(shipment.getId(), "pick", null);
        backend.cancelByCustomer(shipment.getOrderId());

        poller.poll();

        ShipmentEntity cancelled = shipment(shipment.getId());
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getLastActor()).isEqualTo("system");
        assertThat(cancelled.getPickedAt()).isNotNull();
    }

    @Test
    void packedAndShippedShipmentsAreLeftAlone() {
        ShipmentDto packed = releaseToWarehouse(paidOrder().id());
        transition(packed.getId(), "pick", null);
        transition(packed.getId(), "pack", new PackShipmentDto(BigDecimal.ONE));
        ShipmentDto shipped = releaseToWarehouse(paidOrder().id());
        transition(shipped.getId(), "pick", null);
        transition(shipped.getId(), "pack", new PackShipmentDto(BigDecimal.ONE));
        transition(shipped.getId(), "ship", new ShipShipmentDto(Carrier.DHL, "1234567890"));
        backend.publish("order.cancelled", backend.order(packed.getOrderId()));
        backend.publish("order.cancelled", backend.order(shipped.getOrderId()));

        int handled = poller.poll();

        assertThat(handled).isEqualTo(2);
        assertThat(shipment(packed.getId()).getStatus()).isEqualTo(ShipmentStatus.PACKED);
        assertThat(shipment(shipped.getId()).getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(shipment(shipped.getId()).getLastActor()).isEqualTo("warehouse");
        assertThat(poller.cursor()).isEqualTo(2);
    }

    @Test
    void aWarehouseCancellationEchoedByTheFeedIsANoOp() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        ShipmentDto cancelled = transition(shipment.getId(), "cancel", null).as(ShipmentDto.class);

        int handled = poller.poll();

        assertThat(backend.feed()).hasSize(1);
        assertThat(handled).isEqualTo(1);
        ShipmentEntity after = shipment(shipment.getId());
        assertThat(after.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(after.getLastActor()).isEqualTo("warehouse");
        assertThat(after.getCancelledAt()).isEqualTo(cancelled.getCancelledAt());
    }

    @Test
    void anEventIsHandledOnlyOnceEvenWhenTheFeedRepeatsIt() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(shipment.getOrderId());
        FeedEvent original = backend.feed().getFirst();

        int first = poller.poll();
        ShipmentEntity afterFirst = shipment(shipment.getId());
        // The backend replays the same event id with a later sequence; a second instance or a lost
        // cursor update would see it again.
        backend.publish(original);
        int second = poller.poll();
        int third = poller.poll();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(third).isZero();
        assertThat(processedEvents.count()).isEqualTo(1);
        assertThat(shipment(shipment.getId()).getCancelledAt()).isEqualTo(afterFirst.getCancelledAt());
        assertThat(poller.cursor()).isEqualTo(2);
    }

    @Test
    void theCursorIsPersistedAndTheFeedIsReadPageByPage() {
        InternalOrderDto unrelated = paidOrder();
        for (int i = 0; i < 250; i++) {
            backend.publish("order.cancelled", unrelated);
        }
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(shipment.getOrderId());

        int handled = poller.poll();

        assertThat(handled).isEqualTo(251);
        assertThat(backend.calls()).filteredOn(call -> call.startsWith("GET events"))
                .containsExactly("GET events after 0", "GET events after 100", "GET events after 200");
        assertThat(cursors.findById(EventFeedPoller.CONSUMER)).hasValueSatisfying(cursor -> {
            assertThat(cursor.getLastSequence()).isEqualTo(251);
            assertThat(cursor.getUpdatedAt()).isNotNull();
        });
        assertThat(shipment(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);

        assertThat(poller.poll()).isZero();
        assertThat(backend.calls().getLast()).isEqualTo("GET events after 251");
    }

    @Test
    void theCursorFollowsNextCursorPastEventsOfOtherTypes() {
        backend.publish("order.cancelled", paidOrder());
        backend.skipSequences(5);

        poller.poll();
        poller.poll();

        assertThat(poller.cursor()).isEqualTo(6);
        assertThat(backend.calls()).filteredOn(call -> call.startsWith("GET events")).last()
                .isEqualTo("GET events after 6");
    }

    @Test
    void whileTheBackendIsDownTheRunIsSkippedAndTheNextOneCatchesUp() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(shipment.getOrderId());
        backend.setAvailable(false);

        int duringOutage = poller.poll();
        long cursorDuringOutage = poller.cursor();
        ShipmentStatus statusDuringOutage = shipment(shipment.getId()).getStatus();
        backend.setAvailable(true);
        int afterOutage = poller.poll();

        assertThat(duringOutage).isZero();
        assertThat(cursorDuringOutage).isZero();
        assertThat(statusDuringOutage).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(afterOutage).isEqualTo(1);
        assertThat(shipment(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void aCancellationOfAnOrderWithoutShipmentIsRecordedAndSkipped() {
        InternalOrderDto neverReleased = paidOrder();
        backend.cancelByCustomer(neverReleased.id());

        int handled = poller.poll();

        assertThat(handled).isEqualTo(1);
        assertThat(shipmentRepository.findByOrderIdOrderByIdAsc(neverReleased.id())).isEmpty();
        assertThat(poller.cursor()).isEqualTo(1);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void anUnreadableEventIsLoggedAndSkippedWithoutBlockingTheFeed() {
        FeedEvent broken = backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled", Instant.now(),
                null, "client", Map.of("status", "CANCELLED")));
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(shipment.getOrderId());

        int handled = poller.poll();

        assertThat(handled).isEqualTo(1);
        assertThat(processedEvents.existsById(broken.id())).isTrue();
        assertThat(poller.cursor()).isEqualTo(2);
        assertThat(shipment(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(poller.poll()).isZero();
    }

    @Test
    void theOrderIdMayComeFromTheEventData() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled", Instant.now(), null, "client",
                Map.of("orderId", shipment.getOrderId(), "status", "CANCELLED")));

        poller.poll();

        assertThat(shipment(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void eventsOfOtherTypesAreFilteredOutAndTheCursorMovesPastThem() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.paid", Instant.now(), shipment.getOrderId(),
                "client", Map.of()));

        int handled = poller.poll();

        assertThat(handled).isZero();
        assertThat(poller.cursor()).isEqualTo(1);
        assertThat(shipment(shipment.getId()).getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    private ShipmentEntity shipment(Long id) {
        return shipmentRepository.findById(id).orElseThrow();
    }

    private InternalOrderDto paidOrder() {
        return backend.order("client", OrderStatus.PAID, null, backend.product("Phone", "Electronics", "25.00", 10), 1);
    }

    private ShipmentDto releaseToWarehouse(Long orderId) {
        Response response = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(orderId), warehouseToken);
        assertThat(response.status()).isEqualTo(201);
        return response.as(ShipmentDto.class);
    }

    private Response transition(Long shipmentId, String action, Object body) {
        Response response = postJson(WAREHOUSE + "/shipments/" + shipmentId + "/" + action, body, warehouseToken);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response;
    }
}
