package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WarehouseFlowTest extends AbstractFlowTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.awesome.warehouse.service.fulfilment.ShipmentReconciler reconciler;
    @org.springframework.beans.factory.annotation.Autowired
    private com.awesome.warehouse.repository.fulfilment.ShipmentRepository shipmentRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.awesome.warehouse.repository.notification.NotificationOutboxRepository outboxRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.awesome.warehouse.service.notification.NotificationDispatcher dispatcher;

    @Test
    void warehouseOperatorDrivesAnOrderFromQueueToDelivery() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "25.00", 10);
        InternalProductDto apple = backend.product("Apple", "Food", "1.00", 10);
        InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 2, apple, 1);

        PageDto<OrderDto> queue = getJson(WAREHOUSE + "/queue", warehouseToken)
                .as(new TypeReference<PageDto<OrderDto>>() {});
        ShipmentDto created = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken)
                .as(ShipmentDto.class);
        PickListDto pickList = getJson(WAREHOUSE + "/shipments/" + created.getId() + "/pick-list", warehouseToken)
                .as(PickListDto.class);
        transition(created.getId(), "pick", null);
        transition(created.getId(), "pack", new PackShipmentDto(new BigDecimal("2.40")));
        ShipmentDto shipped = transition(created.getId(), "ship",
                new ShipShipmentDto(Carrier.UPS, " 1z999aa10123456784 ")).as(ShipmentDto.class);
        OrderStatus afterShip = backend.order(order.id()).status();
        ShipmentDto delivered = transition(created.getId(), "deliver", null).as(ShipmentDto.class);
        ShipmentDto tracked = getJson(ORDERS + "/" + order.id() + "/shipment", clientToken).as(ShipmentDto.class);

        assertThat(queue.getContent()).extracting(OrderDto::getId).containsExactly(order.id());
        assertThat(queue.getContent().getFirst().getItems().getFirst().getTotalPrice()).isEqualByComparingTo("50.00");
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(created.getUsername()).isEqualTo("client");
        assertThat(created.getTotalUnits()).isEqualTo(3);
        assertThat(created.getCreatedBy()).isEqualTo("warehouse");
        assertThat(pickList.getTotalUnits()).isEqualTo(3);
        assertThat(pickList.getItems()).extracting(i -> i.getProductName()).containsExactly("Phone", "Apple");
        assertThat(pickList.getShippingAddress().getCity()).isEqualTo("Krakow");
        assertThat(shipped.getTrackingNumber()).isEqualTo("1Z999AA10123456784");
        assertThat(afterShip).isEqualTo(OrderStatus.SHIPPED);
        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(backend.order(order.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.DELIVERED);
        assertThat(tracked.getId()).isEqualTo(created.getId());
        assertThat(sentNotifications()).extracting(NotificationRequest::template)
                .containsExactly(NotificationTemplate.ORDER_SHIPPED, NotificationTemplate.ORDER_DELIVERED);
        assertThat(sentNotifications().getFirst()).satisfies(n -> {
            assertThat(n.username()).isEqualTo("client");
            assertThat(n.properties()).containsEntry("orderId", String.valueOf(order.id()));
            assertThat(n.properties()).containsEntry("trackingNumber", "1Z999AA10123456784")
                    .containsEntry("carrier", "UPS");
        });
    }

    @Test
    void transitionsOutOfOrderAndInvalidTrackingNumbersAreRejected() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());

        Response packBeforePick = postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pack",
                new PackShipmentDto(new BigDecimal("1.00")), warehouseToken);
        transition(shipment.getId(), "pick", null);
        Response tooHeavy = postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pack",
                new PackShipmentDto(new BigDecimal("30.01")), warehouseToken);
        transition(shipment.getId(), "pack", new PackShipmentDto(new BigDecimal("1.00")));
        Response badTracking = postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/ship",
                new ShipShipmentDto(Carrier.INPOST, "1234567890"), warehouseToken);

        assertThat(packBeforePick.status()).isEqualTo(409);
        assertThat(packBeforePick.message()).isEqualTo("Cannot pack a shipment in status AWAITING_PICK");
        assertThat(tooHeavy.status()).isEqualTo(400);
        assertThat(tooHeavy.body()).contains("Package weight must be between 0.01 and 30.00 kg");
        assertThat(badTracking.status()).isEqualTo(400);
        assertThat(badTracking.message()).isEqualTo("Tracking number for INPOST must be 24 digits");
        assertThat(backend.order(shipment.getOrderId()).fulfilmentState()).isEqualTo(FulfilmentState.PACKED);
    }

    @Test
    void onlyPaidOrdersWithoutAShipmentCanBeReleased() {
        InternalOrderDto pending = backend.order("client", OrderStatus.PENDING, null,
                backend.product("Phone", "Electronics", "25.00", 10), 1);

        Response notPaid = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(pending.id()), warehouseToken);
        backend.put(com.awesome.warehouse.support.FakeBackend.with(pending, OrderStatus.PAID, FulfilmentState.NONE));
        releaseToWarehouse(pending.id());
        Response twice = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(pending.id()), warehouseToken);
        Response unknown = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(999_999L), warehouseToken);

        assertThat(notPaid.status()).isEqualTo(409);
        assertThat(notPaid.message()).isEqualTo("Only PAID orders can be released to the warehouse");
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.message()).isEqualTo("Order already has a shipment");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Order not found");
    }

    @Test
    void backendRefusalOfAwaitingPickLeavesNoLocalShipment() {
        InternalOrderDto order = paidOrder();
        backend.setRefuseFulfilment(true);

        Response refused = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        backend.setRefuseFulfilment(false);
        PageDto<ShipmentDto> afterRefusal = getJson(WAREHOUSE + "/shipments", warehouseToken)
                .as(new TypeReference<PageDto<ShipmentDto>>() {});

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.message()).isEqualTo("Fulfilment transition not allowed");
        assertThat(afterRefusal.getContent()).isEmpty();
        assertThat(releaseToWarehouse(order.id()).getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void backendOutageIsReportedAs503WithoutChangingTheShipment() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.setAvailable(false);

        Response pick = postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pick", null, warehouseToken);
        Response queue = getJson(WAREHOUSE + "/queue", warehouseToken);
        backend.setAvailable(true);

        assertThat(pick.status()).isEqualTo(503);
        assertThat(pick.message()).isEqualTo("Backend unavailable");
        assertThat(queue.status()).isEqualTo(503);
        assertThat(getJson(WAREHOUSE + "/shipments/" + shipment.getId(), warehouseToken).as(ShipmentDto.class)
                .getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void warehouseCancellationCancelsTheOrderAndRestoresStockOnlyBeforePacking() {
        InternalProductDto product = backend.product("Phone", "Electronics", "25.00", 10);
        InternalOrderDto picked = backend.order("client", OrderStatus.PAID, null, product, 2);
        ShipmentDto pickedShipment = releaseToWarehouse(picked.id());
        transition(pickedShipment.getId(), "pick", null);
        ShipmentDto packedShipment = releaseToWarehouse(paidOrder().id());
        transition(packedShipment.getId(), "pick", null);
        transition(packedShipment.getId(), "pack", new PackShipmentDto(BigDecimal.ONE));

        ShipmentDto cancelled = transition(pickedShipment.getId(), "cancel", null).as(ShipmentDto.class);
        Response cancelPacked = postJson(WAREHOUSE + "/shipments/" + packedShipment.getId() + "/cancel", null,
                warehouseToken);

        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(backend.order(picked.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(backend.stockOf(product.id())).isEqualTo(12);
        assertThat(cancelPacked.status()).isEqualTo(409);
        assertThat(cancelPacked.message()).isEqualTo("Cannot cancel a shipment in status PACKED");
    }

    @Test
    void customerCancellationOnTheBackendIsReconciledOnTheNextTransition() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(shipment.getOrderId());

        Response pick = postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pick", null, warehouseToken);
        ShipmentDto after = getJson(WAREHOUSE + "/shipments/" + shipment.getId(), warehouseToken).as(ShipmentDto.class);

        assertThat(pick.status()).isEqualTo(409);
        assertThat(pick.message()).isEqualTo("Order was cancelled by the customer");
        assertThat(after.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(after.getLastActor()).isEqualTo("system");
        assertThat(after.getCancelledAt()).isNotNull();
        assertThat(after.getPickedAt()).isNull();
    }

    @Test
    void readingAShipmentDoesNotApplyACustomerCancellation() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(shipment.getOrderId());

        ShipmentDto read = getJson(WAREHOUSE + "/shipments/" + shipment.getId(), warehouseToken).as(ShipmentDto.class);
        ShipmentDto readAgain = getJson(WAREHOUSE + "/shipments/" + shipment.getId(), warehouseToken).as(ShipmentDto.class);

        // GET is safe: the cancellation is applied by the event feed poller (EventFeedFlowTest)
        assertThat(read.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(readAgain.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void aReleaseWhoseResponseWasLostKeepsItsShipmentAndCanBeRetried() {
        InternalOrderDto order = paidOrder();
        backend.loseNextFulfilmentResponse();

        ShipmentDto created = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken)
                .as(ShipmentDto.class);
        Response retry = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);

        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.AWAITING_PICK);
        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.message()).isEqualTo("Order already has a shipment");
        assertThat(transition(created.getId(), "pick", null).as(ShipmentDto.class).getStatus())
                .isEqualTo(ShipmentStatus.PICKED);
    }

    @Test
    void anOrderReleasedWithoutALocalShipmentCanBeReleasedAgain() {
        InternalOrderDto order = paidOrder();
        backend.updateFulfilment(order.id(), FulfilmentState.AWAITING_PICK);

        ShipmentDto adopted = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken)
                .as(ShipmentDto.class);

        assertThat(adopted.getOrderId()).isEqualTo(order.id());
        assertThat(adopted.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
    }

    @Test
    void theReconcilerCancelsShipmentsAndAdoptsOrphanedReleases() {
        ShipmentDto cancelledByCustomer = releaseToWarehouse(paidOrder().id());
        ShipmentDto active = releaseToWarehouse(paidOrder().id());
        backend.cancelByCustomer(cancelledByCustomer.getOrderId());
        InternalOrderDto orphan = paidOrder();
        backend.updateFulfilment(orphan.id(), FulfilmentState.AWAITING_PICK);
        backend.put(com.awesome.warehouse.support.FakeBackend.updatedAt(backend.order(orphan.id()),
                java.time.LocalDateTime.now().minusMinutes(10)));

        reconciler.reconcile();

        assertThat(shipmentRepository.findById(cancelledByCustomer.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipmentRepository.findById(active.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.AWAITING_PICK);
        assertThat(shipmentRepository.findByOrderIdOrderByIdAsc(orphan.id())).singleElement().satisfies(adopted -> {
            assertThat(adopted.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK);
            assertThat(adopted.getCreatedBy()).isEqualTo("system");
        });
    }

    @Test
    void theReconcilerSkipsARunWhileTheBackendIsDown() {
        releaseToWarehouse(paidOrder().id());
        backend.setAvailable(false);

        reconciler.reconcile();

        backend.setAvailable(true);
        assertThat(shipmentRepository.findAll()).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(ShipmentStatus.AWAITING_PICK));
    }

    @Test
    void notificationsThatTheBackendDidNotAcceptAreRetriedFromTheOutbox() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        transition(shipment.getId(), "pick", null);
        transition(shipment.getId(), "pack", new PackShipmentDto(BigDecimal.ONE));
        backend.setNotificationsDown(true);

        ShipmentDto shipped = transition(shipment.getId(), "ship", new ShipShipmentDto(Carrier.DHL, "1234567890"))
                .as(ShipmentDto.class);
        awaitNotificationDispatch();
        var pending = outboxRepository.findAll();
        backend.setNotificationsDown(false);
        pending.forEach(row -> {
            row.setNextAttemptAt(java.time.LocalDateTime.now().minusSeconds(1));
            outboxRepository.save(row);
        });
        int sent = dispatcher.dispatchDue();

        assertThat(shipped.getStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        assertThat(pending).singleElement().satisfies(row -> {
            assertThat(row.getSentAt()).isNull();
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).isEqualTo("503 Backend unavailable");
        });
        assertThat(sent).isEqualTo(1);
        assertThat(sentNotifications()).singleElement().satisfies(n -> {
            assertThat(n.template()).isEqualTo(NotificationTemplate.ORDER_SHIPPED);
            assertThat(n.requestId()).isEqualTo(pending.getFirst().getRequestId());
        });
        assertThat(outboxRepository.findAll()).singleElement().satisfies(row -> assertThat(row.getSentAt()).isNotNull());
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    @Test
    void clientsCannotUseWarehouseEndpointsOrTrackOtherPeoplesOrders() {
        ShipmentDto shipment = releaseToWarehouse(paidOrder().id());
        InternalOrderDto unreleased = paidOrder();

        assertThat(getJson(WAREHOUSE + "/queue", clientToken).status()).isEqualTo(403);
        assertThat(getJson(WAREHOUSE + "/queue", clientToken).message()).isEqualTo("Access denied");
        assertThat(postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pick", null, clientToken).status())
                .isEqualTo(403);
        assertThat(getJson(ORDERS + "/" + shipment.getOrderId() + "/shipment", otherClientToken).status())
                .isEqualTo(404);
        assertThat(getJson(ORDERS + "/" + shipment.getOrderId() + "/shipment", adminToken).status()).isEqualTo(200);
        assertThat(getJson(ORDERS + "/" + shipment.getOrderId() + "/shipment", warehouseToken).status())
                .isEqualTo(404);
        assertThat(getJson(ORDERS + "/" + unreleased.id() + "/shipment", clientToken).message())
                .isEqualTo("Shipment not found");
        assertThat(getJson(ORDERS + "/" + unreleased.id() + "/shipment", otherClientToken).message())
                .isEqualTo("Order not found");
        assertThat(getJson(WAREHOUSE + "/queue", adminToken).status()).isEqualTo(200);
        assertThat(getJson(WAREHOUSE + "/queue", null).status()).isEqualTo(401);
        assertThat(getJson(WAREHOUSE + "/queue", null).message()).isEqualTo("Unauthorized");
        assertThat(getJson(WAREHOUSE + "/queue", "not-a-jwt").message()).isEqualTo("Invalid or expired token");
    }

    @Test
    void shipmentsCanBeListedByStatus() {
        ShipmentDto first = releaseToWarehouse(paidOrder().id());
        ShipmentDto second = releaseToWarehouse(paidOrder().id());
        transition(second.getId(), "pick", null);

        PageDto<ShipmentDto> picked = getJson(WAREHOUSE + "/shipments?status=PICKED&size=500&page=-3", warehouseToken)
                .as(new TypeReference<PageDto<ShipmentDto>>() {});
        PageDto<ShipmentDto> all = getJson(WAREHOUSE + "/shipments", warehouseToken)
                .as(new TypeReference<PageDto<ShipmentDto>>() {});

        assertThat(picked.getContent()).extracting(ShipmentDto::getId).containsExactly(second.getId());
        assertThat(picked.getPageSize()).isEqualTo(100);
        assertThat(all.getContent()).extracting(ShipmentDto::getId).containsExactly(first.getId(), second.getId());
        assertThat(getJson(WAREHOUSE + "/shipments/999999", warehouseToken).message()).isEqualTo("Shipment not found");
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
