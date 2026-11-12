package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.returns.CreateReturnDto;
import com.awesome.warehouse.dto.returns.ReturnItemRequestDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.returns.ReturnReason;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent requests against the production pool size of three connections. A request
 * that needs a second connection while it holds one (a REQUIRES_NEW transaction under an
 * open one, or an after-commit listener that runs before the request's connection is
 * released) starves the pool and fails after the connection timeout.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=3000"})
class ConnectionPoolFlowTest extends AbstractFlowTest {

    private static final int ORDER_COUNT = 8;
    private static final Duration BACKEND_LATENCY = Duration.ofMillis(25);

    @Test
    void eightOrdersAreReleasedPickedPackedAndShippedConcurrentlyOnThreeConnections() throws Exception {
        InternalProductDto phone = backend.product("Phone", "Electronics", "25.00", 100);
        List<InternalOrderDto> orders = IntStream.range(0, ORDER_COUNT)
                .mapToObj(i -> backend.order("client", OrderStatus.PAID, null, phone, 1))
                .toList();
        backend.setLatency(BACKEND_LATENCY);

        List<Response> created = concurrently(orders, order ->
                postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken));
        assertThat(created).extracting(Response::status).as(bodies(created)).containsOnly(201);
        List<Long> shipments = created.stream().map(r -> r.as(ShipmentDto.class).getId()).toList();

        List<Response> picked = concurrently(shipments, id -> transition(id, "pick", null));
        List<Response> packed = concurrently(shipments, id ->
                transition(id, "pack", new PackShipmentDto(BigDecimal.ONE)));
        List<Response> shipped = concurrently(shipments, id ->
                transition(id, "ship", new ShipShipmentDto(Carrier.DHL, "1234567890")));

        assertThat(picked).extracting(Response::status).as(bodies(picked)).containsOnly(200);
        assertThat(packed).extracting(Response::status).as(bodies(packed)).containsOnly(200);
        assertThat(shipped).extracting(Response::status).as(bodies(shipped)).containsOnly(200);
        assertThat(sentNotifications()).extracting(NotificationRequest::template)
                .containsOnly(NotificationTemplate.ORDER_SHIPPED);
        assertThat(sentNotifications()).extracting(n -> n.properties().get("orderId"))
                .containsExactlyInAnyOrderElementsOf(orders.stream().map(o -> String.valueOf(o.id())).toList());
    }

    @Test
    void eightReturnsAreRequestedConcurrentlyOnThreeConnections() throws Exception {
        InternalProductDto phone = backend.product("Phone", "Electronics", "25.00", 100);
        List<InternalOrderDto> orders = IntStream.range(0, ORDER_COUNT)
                .mapToObj(i -> backend.order("client", OrderStatus.DELIVERED, null, phone, 1))
                .toList();
        backend.setLatency(BACKEND_LATENCY);

        List<Response> requested = concurrently(orders, order -> postJson(ORDERS + "/" + order.id() + "/returns",
                new CreateReturnDto(ReturnReason.NOT_AS_DESCRIBED, null,
                        List.of(new ReturnItemRequestDto(phone.id(), 1))), clientToken));

        assertThat(requested).extracting(Response::status).as(bodies(requested)).containsOnly(201);
    }

    @Test
    void concurrentReturnsOfOneOrderStillCannotReturnMoreUnitsThanWereBought() throws Exception {
        InternalProductDto phone = backend.product("Phone", "Electronics", "25.00", 100);
        InternalOrderDto order = backend.order("client", OrderStatus.DELIVERED, null, phone, 3);

        List<Response> requested = concurrently(IntStream.range(0, ORDER_COUNT).boxed().toList(), i ->
                postJson(ORDERS + "/" + order.id() + "/returns", new CreateReturnDto(ReturnReason.NOT_AS_DESCRIBED,
                        null, List.of(new ReturnItemRequestDto(phone.id(), 1))), clientToken));

        assertThat(requested).extracting(Response::status).as(bodies(requested))
                .containsOnly(201, 409)
                .filteredOn(status -> status == 201).hasSize(3);
    }

    private Response transition(Long shipmentId, String action, Object body) {
        return postJson(WAREHOUSE + "/shipments/" + shipmentId + "/" + action, body, warehouseToken);
    }

    /** Sends one request per input at the same moment and waits for all of them. */
    private <T> List<Response> concurrently(List<T> inputs, Function<T, Response> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (T input : inputs) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.apply(input);
                }));
            }
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String bodies(List<Response> responses) {
        return responses.stream().map(Response::body).toList().toString();
    }
}
