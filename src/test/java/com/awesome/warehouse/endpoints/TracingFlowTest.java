package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.RestBackendClient;
import com.awesome.warehouse.correlation.CorrelationInterceptor;
import com.awesome.warehouse.correlation.TracingSupport;
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
import com.awesome.warehouse.service.tracking.CarrierSimulator;
import com.awesome.warehouse.support.AbstractFlowTest;
import com.awesome.warehouse.support.FakeBackend.CorrelatedCall;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tracing on ({@code TRACING_ENABLED=true}), with spans collected in memory instead of the OTLP
 * exporter: server spans continue the incoming {@code traceparent}, the MDC (and so the logs and
 * the backend calls) carries the span's ids, feed events and outbox deliveries are spans parented
 * by the trace context they carry, and the instrumented client propagates its own span.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "warehouse.tracing.enabled=true",
    "management.tracing.export.otlp.enabled=false"
})
class TracingFlowTest extends AbstractFlowTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String CALLER_SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + CALLER_SPAN_ID + "-01";
    private static final String REQUEST_ID = "checkout-42";
    private static final AttributeKey<String> EVENT_TRACE_ID = AttributeKey.stringKey("event.trace_id");

    @Autowired private InMemorySpanExporter spans;
    @Autowired private NotificationOutboxRepository outbox;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private EventFeedPoller poller;
    @Autowired private TracingSupport tracing;
    @Autowired private Tracer tracer;
    @Autowired private ObservationRegistry observations;
    @Autowired private RestClient.Builder restClientBuilder;
    @Autowired private CarrierSimulator carrierSimulator;

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemorySpans {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SpanProcessor inMemorySpanProcessor(InMemorySpanExporter exporter) {
            return SimpleSpanProcessor.create(exporter);
        }
    }

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void theServerSpanContinuesTheIncomingTraceAndItsIdsAreInTheMdc(CapturedOutput output) throws Exception {
        InternalOrderDto order = paidOrder();
        int callsBefore = backend.correlatedCalls().size();

        MvcResult result = perform(post(WAREHOUSE + "/shipments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new CreateShipmentDto(order.id()))), REQUEST_ID, TRACEPARENT);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo(REQUEST_ID);
        SpanData server = singleServerSpan();
        assertThat(server.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(server.getParentSpanId()).isEqualTo(CALLER_SPAN_ID);
        assertThat(server.getParentSpanContext().isRemote()).isTrue();
        assertThat(backend.correlatedCalls().subList(callsBefore, backend.correlatedCalls().size()))
                .isNotEmpty()
                .allSatisfy(call -> assertThat(call).isEqualTo(new CorrelatedCall(call.call(), REQUEST_ID, TRACE_ID)));
        assertThat(MDC.get("traceId")).as("the filter clears the MDC").isNull();
    }

    @Test
    void withoutTraceparentTheServerSpanStartsATraceAndTheLogsUseItsId() throws Exception {
        InternalOrderDto order = paidOrder();
        int callsBefore = backend.correlatedCalls().size();

        perform(post(WAREHOUSE + "/shipments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new CreateShipmentDto(order.id()))), null, "00-xyz-01");

        SpanData server = singleServerSpan();
        assertThat(server.getParentSpanContext().isValid()).isFalse();
        assertThat(backend.correlatedCalls().subList(callsBefore, backend.correlatedCalls().size()))
                .isNotEmpty()
                .allSatisfy(call -> assertThat(call.traceId()).isEqualTo(server.getTraceId()));
    }

    @Test
    void theOutboxStoresTheServerSpanAndTheDeliveryIsItsChild() throws Exception {
        ShipmentDto shipment = postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(paidOrder().id()),
                warehouseToken).as(ShipmentDto.class);
        postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pick", null, warehouseToken);
        postJson(WAREHOUSE + "/shipments/" + shipment.getId() + "/pack", new PackShipmentDto(BigDecimal.ONE),
                warehouseToken);
        spans.reset();

        perform(post(WAREHOUSE + "/shipments/" + shipment.getId() + "/ship")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(new ShipShipmentDto(Carrier.DHL, "1234567890"))),
                REQUEST_ID, TRACEPARENT);
        awaitNotificationDispatch();

        SpanData server = singleServerSpan();
        NotificationOutboxEntity row = outbox.findAll().getFirst();
        // The stored parent is the span current at enqueue time: the server span or (with Spring
        // Security's observations) one of its descendants in the same trace.
        assertThat(row.getTraceparent()).matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01");
        String storedSpanId = row.getTraceparent().substring(36, 52);
        assertThat(ancestorsOf(storedSpanId)).contains(server.getSpanId());
        assertThat(row.getSentAt()).isNotNull();
        SpanData delivery = spanNamed("send ORDER_SHIPPED");
        assertThat(delivery.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(delivery.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(delivery.getParentSpanId()).isEqualTo(storedSpanId);
        assertThat(delivery.getAttributes().get(AttributeKey.stringKey("outbox.request_id")))
                .isEqualTo(row.getRequestId().toString());
        assertThat(backend.correlatedCalls()).contains(
                new CorrelatedCall("POST notification ORDER_SHIPPED", REQUEST_ID, TRACE_ID));
    }

    @Test
    void aRetriedRowWithoutTraceparentIsDeliveredInANewTrace() {
        outbox.save(NotificationOutboxEntity.builder().requestId(UUID.randomUUID()).username("client")
                .template(com.awesome.warehouse.client.NotificationTemplate.ORDER_SHIPPED)
                .properties("{\"orderId\":\"7\"}").attempts(1).nextAttemptAt(java.time.LocalDateTime.now().minusMinutes(1))
                .createdAt(java.time.LocalDateTime.now()).build());

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        SpanData delivery = spanNamed("send ORDER_SHIPPED");
        assertThat(delivery.getParentSpanContext().isValid()).isFalse();
    }

    @Test
    void eachFeedEventIsAConsumerSpanParentedByTheSpanThatWroteIt(CapturedOutput output) {
        InternalOrderDto order = paidOrder();
        postJson(WAREHOUSE + "/shipments", new CreateShipmentDto(order.id()), warehouseToken);
        spans.reset();
        FeedEvent parented = backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled", Instant.now(),
                order.id(), "client", Map.of("orderId", order.id()), "cancel-" + order.id(), TRACE_ID,
                CALLER_SPAN_ID));
        FeedEvent traceOnly = backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled", Instant.now(),
                order.id(), "client", Map.of("orderId", order.id()), "cancel-again", TRACE_ID));
        FeedEvent withoutIds = backend.publish(new FeedEvent(0, UUID.randomUUID(), "order.cancelled",
                Instant.now(), order.id(), "client", Map.of("orderId", order.id())));

        assertThat(poller.poll()).isEqualTo(3);

        List<SpanData> consumers = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.CONSUMER).toList();
        assertThat(consumers).hasSize(3).allSatisfy(span -> assertThat(span.getName()).isEqualTo("process order.cancelled"));
        SpanData first = consumerOf(consumers, parented);
        assertThat(first.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(first.getParentSpanId()).isEqualTo(CALLER_SPAN_ID);
        assertThat(first.getAttributes().get(AttributeKey.stringKey("event.sequence")))
                .isEqualTo(Long.toString(parented.sequence()));
        SpanData second = consumerOf(consumers, traceOnly);
        assertThat(second.getParentSpanContext().isValid()).as("no span id: a new trace").isFalse();
        assertThat(second.getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(second.getAttributes().get(EVENT_TRACE_ID)).isEqualTo(TRACE_ID);
        SpanData third = consumerOf(consumers, withoutIds);
        assertThat(third.getParentSpanContext().isValid()).isFalse();
        assertThat(third.getAttributes().get(EVENT_TRACE_ID)).isNull();
        assertThat(output.getOut())
                .containsPattern("\\[" + TRACE_ID + ",cancel-" + order.id() + "\\] .*Handled event " + parented.id())
                .containsPattern("\\[" + second.getTraceId() + ",cancel-again\\] .*Handled event " + traceOnly.id());
    }

    @Test
    void theInstrumentedClientSendsItsOwnSpanInsteadOfAHandBuiltTraceparent() {
        RestClient.Builder builder = restClientBuilder.clone();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<HttpHeaders> sent = new AtomicReference<>();
        server.expect(requestTo("http://backend.test/internal/v1/jwks.json"))
                .andExpect(request -> sent.set(request.getHeaders()))
                .andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));
        RestBackendClient client = new RestBackendClient(builder, "http://backend.test", "token",
                new CorrelationInterceptor(tracing.enabled()));

        tracing.consume("process test", "req-9", TRACE_ID, CALLER_SPAN_ID, Map.of(), client::jwks);

        SpanData clientSpan = spans.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.CLIENT).findFirst().orElseThrow();
        SpanData consumer = spanNamed("process test");
        assertThat(clientSpan.getParentSpanId()).isEqualTo(consumer.getSpanId());
        assertThat(sent.get().getFirst("traceparent"))
                .isEqualTo("00-" + TRACE_ID + "-" + clientSpan.getSpanId() + "-01");
        assertThat(sent.get().getFirst("X-Request-Id")).isEqualTo("req-9");
    }

    @Test
    void aFailingDeliveryEndsItsSpanAsAnErrorAndRestoresTheMdc() {
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> tracing.deliver("send TEST", "req-1", TRACEPARENT, Map.of(), () -> {
            throw failure;
        })).isSameAs(failure);

        SpanData delivery = spanNamed("send TEST");
        assertThat(delivery.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(delivery.getParentSpanId()).isEqualTo(CALLER_SPAN_ID);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void anUnsampledSpanIsStoredAsAnUnsampledTraceparent() {
        String unsampled = "00-" + TRACE_ID + "-" + CALLER_SPAN_ID + "-00";

        String stored = tracing.deliver("send TEST", "req-1", unsampled, Map.of(),
                () -> tracing.currentTraceparent().orElseThrow());

        assertThat(stored).startsWith("00-" + TRACE_ID + "-").endsWith("-00");
        assertThat(spans.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void carrierStepsAreRootSpansWhileFeedPollsAndNotificationRetriesAreNotRecorded() throws Exception {
        AtomicReference<io.micrometer.tracing.Span> carrierRun = new AtomicReference<>();
        observeScheduledRun(carrierSimulator, "scheduledStep", carrierRun);
        AtomicReference<io.micrometer.tracing.Span> pollRun = new AtomicReference<>();
        observeScheduledRun(poller, "scheduledPoll", pollRun);
        AtomicReference<io.micrometer.tracing.Span> retryRun = new AtomicReference<>();
        observeScheduledRun(dispatcher, "retryDue", retryRun);

        assertThat(carrierRun.get().context().sampled()).isTrue();
        assertThat(carrierRun.get().context().parentId()).isNull();
        assertThat(pollRun.get().context().sampled()).isFalse();
        assertThat(retryRun.get().context().sampled()).isFalse();
        assertThat(spans.getFinishedSpanItems()).singleElement()
                .satisfies(span -> assertThat(span.getSpanId()).isEqualTo(carrierRun.get().context().spanId()));
    }

    private void observeScheduledRun(Object bean, String method, AtomicReference<io.micrometer.tracing.Span> span)
            throws NoSuchMethodException {
        ScheduledTaskObservationContext context = new ScheduledTaskObservationContext(bean,
                bean.getClass().getMethod(method));
        Observation.createNotStarted("tasks.scheduled.execution", () -> context, observations)
                .observe(() -> span.set(tracer.currentSpan()));
    }

    private SpanData singleServerSpan() {
        return spans.getFinishedSpanItems().stream().filter(span -> span.getKind() == SpanKind.SERVER)
                .reduce((a, b) -> {
                    throw new AssertionError("more than one server span: " + spans.getFinishedSpanItems());
                }).orElseThrow();
    }

    /** The span itself and every ancestor recorded in this test. */
    private List<String> ancestorsOf(String spanId) {
        Map<String, String> parents = new java.util.HashMap<>();
        spans.getFinishedSpanItems().forEach(span -> parents.put(span.getSpanId(), span.getParentSpanId()));
        List<String> chain = new java.util.ArrayList<>();
        for (String id = spanId; parents.containsKey(id); id = parents.get(id)) {
            chain.add(id);
        }
        return chain;
    }

    private SpanData spanNamed(String name) {
        return spans.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span " + name + " in " + spans.getFinishedSpanItems()));
    }

    private static SpanData consumerOf(List<SpanData> consumers, FeedEvent event) {
        return consumers.stream()
                .filter(span -> event.id().toString().equals(span.getAttributes().get(AttributeKey.stringKey("event.id"))))
                .findFirst().orElseThrow();
    }

    private InternalOrderDto paidOrder() {
        return backend.order("client", OrderStatus.PAID, null, backend.product("Phone", "Electronics", "25.00", 10), 1);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder, String requestId, String traceparent)
            throws Exception {
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseToken);
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        if (traceparent != null) {
            builder.header("traceparent", traceparent);
        }
        return mockMvc.perform(builder).andReturn();
    }
}
