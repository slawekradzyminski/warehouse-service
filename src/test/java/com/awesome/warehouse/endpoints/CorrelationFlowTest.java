package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.notification.NotificationOutboxEntity;
import com.awesome.warehouse.repository.notification.NotificationOutboxRepository;
import com.awesome.warehouse.service.event.EventFeedPoller;
import com.awesome.warehouse.service.notification.NotificationDispatcher;
import com.awesome.warehouse.support.AbstractFlowTest;
import com.awesome.warehouse.support.FakeBackend.CorrelatedCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Correlation ids end to end: response header, backend calls, the notification outbox and the event feed. */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationFlowTest extends AbstractFlowTest {

    private static final String REQUEST_ID = "checkout-42.retry_1";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private EventFeedPoller poller;

    @Test
    void aValidRequestIdIsReturnedAndUsedForEveryBackendCallOfTheRequest() throws Exception {
        InternalOrderDto order = paidOrder();
        int callsBefore = backend.correlatedCalls().size();

        MvcResult result = perform(post(WAREHOUSE + "/shipments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new CreateShipmentDto(order.id()))), warehouseToken, REQUEST_ID, TRACEPARENT);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo(REQUEST_ID);
        assertThat(backend.correlatedCalls().subList(callsBefore, backend.correlatedCalls().size()))
                .isNotEmpty()
                .allSatisfy(call -> {
                    assertThat(call.requestId()).isEqualTo(REQUEST_ID);
                    assertThat(call.traceId()).isEqualTo(TRACE_ID);
                });
        assertThat(MDC.get("requestId")).as("the filter clears the MDC").isNull();
    }

    @Test
    void invalidIdsAreReplacedAndEvenRejectedRequestsGetARequestId() throws Exception {
        MvcResult unauthenticated = perform(get(WAREHOUSE + "/queue"), null, "bad id with spaces", "00-xyz-01");
        MvcResult missing = perform(get("/actuator/health"), null, null, null);

        assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
        assertThat(unauthenticated.getResponse().getHeader("X-Request-Id"))
                .matches("[0-9a-f-]{36}").isNotEqualTo("bad id with spaces");
        assertThat(missing.getResponse().getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void notificationsKeepTheIdsOfTheRequestThatQueuedThemAndSendThemOnRetry(CapturedOutput output)
            throws Exception {
        ShipmentDto shipment = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(paidOrder().id()),
                warehouseToken).as(ShipmentDto.class);
        postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pick", null, warehouseToken);
        postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pack", new PackShipmentDto(BigDecimal.ONE),
                warehouseToken);
        backend.setNotificationsDown(true);

        perform(post(WAREHOUSE + "/shipments/" + shipment.getId() + "/ship")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new ShipShipmentDto(Carrier.DHL, "1234567890"))),
                warehouseToken, REQUEST_ID, TRACEPARENT);
        NotificationOutboxEntity queued = outbox.findAll().getFirst();
        backend.setNotificationsDown(false);
        queued.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        outbox.save(queued);
        int callsBefore = backend.correlatedCalls().size();
        int sent = dispatcher.dispatchDue();

        assertThat(queued.getCorrelationId()).isEqualTo(REQUEST_ID);
        assertThat(queued.getTraceparent()).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01")
                .as("the stored parent is this service's span, not the caller's")
                .isNotEqualTo(TRACEPARENT);
        assertThat(sent).isEqualTo(1);
        assertThat(backend.correlatedCalls().subList(callsBefore, backend.correlatedCalls().size()))
                .singleElement()
                .isEqualTo(new CorrelatedCall("POST notification ORDER_SHIPPED", REQUEST_ID, TRACE_ID));
        assertThat(output.getOut()).contains("[" + TRACE_ID + "," + REQUEST_ID + "]");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void anEventIsHandledAndLoggedUnderTheIdsOfTheBackendRequestThatWroteIt(CapturedOutput output) {
        InternalOrderDto order = paidOrder();
        postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        FeedEvent event = backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled", Instant.now(),
                order.id(), "client", Map.of("orderId", order.id()), "cancel-" + order.id(), TRACE_ID));
        FeedEvent withoutIds = backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled",
                Instant.now(), order.id(), "client", Map.of("orderId", order.id())));

        assertThat(poller.poll()).isEqualTo(2);

        assertThat(output.getOut())
                .containsPattern("\\[" + TRACE_ID + ",cancel-" + order.id() + "\\] .*Handled event " + event.id())
                .containsPattern("\\[[0-9a-f]{32},[0-9a-f-]{36}\\] .*Handled event " + withoutIds.id());
        assertThat(MDC.get("traceId")).isNull();
    }

    private InternalOrderDto paidOrder() {
        return backend.order("client", OrderStatus.PAID, null, backend.product("Phone", "Electronics", "25.00", 10), 1);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, String token, String requestId,
                              String traceparent) throws Exception {
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        if (traceparent != null) {
            builder.header("traceparent", traceparent);
        }
        return mockMvc.perform(builder).andReturn();
    }
}
