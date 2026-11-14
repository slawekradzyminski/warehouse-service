package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import com.awesome.warehouse.support.AbstractFlowTest;
import com.awesome.warehouse.support.FakeBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races between writers of one split order, on the production pool size of three
 * connections. Every fulfilment write takes the order's lock row first, so the aggregate
 * is always computed and mirrored by one writer at a time.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=5000"})
class MultiWarehouseConcurrencyFlowTest extends AbstractFlowTest {

    private static final int ROUNDS = 6;
    private static final Duration BACKEND_LATENCY = Duration.ofMillis(30);

    @Autowired private ShipmentService shipmentService;
    @Autowired private ShipmentRepository shipmentRepository;

    private InternalProductDto phone;
    private InternalProductDto novel;

    @BeforeEach
    void catalogue() {
        useDemoSplit();
        phone = backend.product("Phone", "Electronics", "25.00", 500);
        novel = backend.product("Novel", "Books", "10.00", 500);
    }

    /**
     * Without one lock per order both packers would see the other parcel still PICKED, both
     * would compute the aggregate PICKED, and the backend would never learn PACKED.
     */
    @Test
    void packingBothParcelsAtOnceMirrorsPackedExactlyOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            backend.setLatency(Duration.ZERO);
            InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
            List<ShipmentDto> parcels = releaseAll(order);
            parcels.forEach(parcel -> assertThat(transition(parcel.getId(), "pick", null).status()).isEqualTo(200));
            backend.setLatency(BACKEND_LATENCY);

            List<Response> packed = concurrently(
                    () -> transition(parcels.get(0).getId(), "pack", new PackShipmentDto(BigDecimal.ONE)),
                    () -> transition(parcels.get(1).getId(), "pack", new PackShipmentDto(BigDecimal.ONE)));

            assertThat(packed).extracting(Response::status).containsOnly(200);
            assertThat(backend.order(order.id()).fulfilmentState()).as("round " + round).isEqualTo(FulfilmentState.PACKED);
            assertThat(backend.calls()).as("round " + round)
                    .filteredOn(("PUT fulfilment " + order.id() + " PACKED")::equals).hasSize(1);
        }
    }

    @Test
    void pickingBothParcelsAtOnceMirrorsPickedOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            backend.setLatency(Duration.ZERO);
            InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
            List<ShipmentDto> parcels = releaseAll(order);
            backend.setLatency(BACKEND_LATENCY);

            List<Response> picked = concurrently(
                    () -> transition(parcels.get(0).getId(), "pick", null),
                    () -> transition(parcels.get(1).getId(), "pick", null));

            assertThat(picked).extracting(Response::status).containsOnly(200);
            assertThat(backend.calls()).filteredOn(("PUT fulfilment " + order.id() + " PICKED")::equals).hasSize(1);
        }
    }

    /**
     * The backend decides the race between picking one parcel and the customer cancelling:
     * either the cancellation wins (every parcel ends CANCELLED and the pick is refused) or
     * the pick wins (the backend is PICKED and refuses the cancellation). Never a mix.
     */
    @Test
    void pickingOneParcelWhileTheCustomerCancelsEndsConsistently() throws Exception {
        for (int round = 0; round < ROUNDS * 2; round++) {
            backend.setLatency(Duration.ZERO);
            InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
            List<ShipmentDto> parcels = releaseAll(order);
            backend.setLatency(BACKEND_LATENCY);
            boolean[] cancelled = new boolean[1];

            List<Object> results = concurrentlyAny(
                    () -> transition(parcels.get(0).getId(), "pick", null),
                    () -> {
                        cancelled[0] = backend.tryCancelByCustomer(order.id());
                        // The event feed delivers the cancellation (EventFeedPoller calls this).
                        return cancelled[0] && shipmentService.followOrderCancellation(order.id());
                    });
            Response pick = (Response) results.get(0);
            List<ShipmentEntity> after = shipmentRepository.findByOrderIdOrderByIdAsc(order.id());
            String context = "round " + round + " pick " + pick.body();

            if (cancelled[0]) {
                assertThat(pick.status()).as(context).isEqualTo(409);
                assertThat(after).as(context).extracting(ShipmentEntity::getStatus).containsOnly(ShipmentStatus.CANCELLED);
                assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.CANCELLED);
            } else {
                assertThat(pick.status()).as(context).isEqualTo(200);
                assertThat(after).as(context).extracting(ShipmentEntity::getStatus)
                        .containsExactly(ShipmentStatus.PICKED, ShipmentStatus.AWAITING_PICK);
                assertThat(backend.order(order.id()).fulfilmentState()).isEqualTo(FulfilmentState.PICKED);
            }
        }
    }

    /**
     * An order the backend already shows AWAITING_PICK (a release whose local write was lost)
     * may be adopted by the reconciler while an operator releases it again: the parcels are
     * created exactly once, by whichever takes the order lock first.
     */
    @Test
    void adoptionAndAReleaseOfTheSameOrderCreateItsParcelsOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            backend.setLatency(Duration.ZERO);
            InternalOrderDto order = backend.order("client", OrderStatus.PAID, null, phone, 1, novel, 1);
            backend.updateFulfilment(order.id(), FulfilmentState.AWAITING_PICK);
            backend.put(FakeBackend.updatedAt(backend.order(order.id()), LocalDateTime.now().minusMinutes(10)));
            backend.setLatency(BACKEND_LATENCY);

            List<Object> results = concurrentlyAny(
                    () -> postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken),
                    shipmentService::adoptReleasedOrders);
            Response release = (Response) results.get(0);
            int adopted = (Integer) results.get(1);

            assertThat(release.status()).as(release.body()).isIn(201, 409);
            assertThat((release.status() == 201 ? 1 : 0) + adopted).as("round " + round).isEqualTo(1);
            assertThat(shipmentRepository.findByOrderIdOrderByIdAsc(order.id()))
                    .extracting(ShipmentEntity::getLocationCode).containsExactly("KRK", "GDN");
            backend.setLatency(Duration.ZERO);
            shipmentRepository.findByOrderIdOrderByIdAsc(order.id()).forEach(parcel ->
                    assertThat(transition(parcel.getId(), "cancel", null).status()).isIn(200, 409));
        }
    }

    private List<ShipmentDto> releaseAll(InternalOrderDto order) {
        Response created = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return getJson(ORDERS + "/" + order.id() + "/shipments", adminToken).as(new TypeReference<List<ShipmentDto>>() {});
    }

    private Response transition(Long shipmentId, String action, Object body) {
        return postJson(WAREHOUSE + "/shipments/" + shipmentId + "/" + action, body, warehouseToken);
    }

    @SafeVarargs
    private List<Response> concurrently(Callable<Response>... calls) throws Exception {
        return concurrentlyAny(calls).stream().map(Response.class::cast).toList();
    }

    /** Starts every call at the same moment and waits for all of them, in argument order. */
    private List<Object> concurrentlyAny(Callable<?>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (Callable<?> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Object> results = new java.util.ArrayList<>();
            for (Future<?> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
