package com.awesome.warehouse.correlation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Joins the correlation ids with OpenTelemetry tracing ({@code TRACING_ENABLED}).
 *
 * <p>Tracing off (the default): every method behaves exactly like the plain
 * {@link CorrelationContext} factories; no span is started here and the sampler records none.
 *
 * <p>Tracing on: the ids put into the MDC are the ids of the current span, so the trace id in
 * the logs is the one exported to the collector. Feed events and outbox deliveries run in their
 * own span whose parent is the trace context they carry. The MDC keys stay
 * {@code requestId}, {@code traceId} and {@code spanId}; this class (not Micrometer's MDC
 * listener, which {@code TracingConfig} silences) owns them.
 */
@Component
public class TracingSupport {

    private final boolean tracingEnabled;
    private final Tracer tracer;

    @Autowired
    public TracingSupport(@Value("${warehouse.tracing.enabled:false}") boolean enabled, Tracer tracer) {
        this.tracingEnabled = enabled;
        this.tracer = tracer;
    }

    /** Tracing off; for unit tests that build services by hand. */
    public static TracingSupport disabled() {
        return new TracingSupport(false, Tracer.NOOP);
    }

    /** Whether spans are recorded and propagated by the instrumented HTTP client. */
    public boolean enabled() {
        return tracingEnabled;
    }

    /**
     * Ids of an incoming request. With tracing on the server span (whose parent is a valid
     * incoming {@code traceparent}) gives the trace and span id; otherwise see
     * {@link CorrelationContext#fromHeaders}.
     */
    public CorrelationContext forRequest(String requestId, String traceparent) {
        return currentSpan()
                .map(span -> withIds(requestId, span.context()))
                .orElseGet(() -> CorrelationContext.fromHeaders(requestId, traceparent));
    }

    /** Ids of one scheduled run: the run's span with tracing on, fresh ids otherwise. */
    public CorrelationContext forJob() {
        return currentSpan()
                .map(span -> withIds(null, span.context()))
                .orElseGet(CorrelationContext::generate);
    }

    /**
     * The {@code traceparent} to store with a hand-off (the notification outbox): the current
     * span with tracing on, otherwise the ids in the MDC.
     */
    public Optional<String> currentTraceparent() {
        Optional<String> fromSpan = currentSpan().map(span -> new CorrelationContext.Traceparent(
                span.context().traceId(), span.context().spanId(),
                Boolean.TRUE.equals(span.context().sampled())).value());
        return fromSpan.isPresent() ? fromSpan : CorrelationContext.current().map(CorrelationContext::traceparent);
    }

    /**
     * Runs the handling of a feed event. Tracing on: a {@code CONSUMER} span named
     * {@code name} whose parent is the event's {@code traceId}/{@code spanId}; an event without
     * a valid span id starts a new trace that records the event's trace id as the
     * {@code event.trace_id} attribute. Tracing off: the event's ids, see
     * {@link CorrelationContext#fromIds}.
     */
    public <T> T consume(String name, String requestId, String traceId, String spanId, Map<String, String> tags,
                         Supplier<T> work) {
        if (!tracingEnabled) {
            return CorrelationContext.fromIds(requestId, traceId).call(work);
        }
        Optional<String> validTraceId = CorrelationContext.validTraceId(traceId);
        Optional<String> validSpanId = CorrelationContext.validSpanId(spanId);
        Span.Builder builder = validTraceId.isPresent() && validSpanId.isPresent()
                ? childOf(validTraceId.get(), validSpanId.get(), true)
                : tracer.spanBuilder().setNoParent();
        if (validTraceId.isPresent() && validSpanId.isEmpty()) {
            builder.tag("event.trace_id", validTraceId.get());
        }
        tags.forEach(builder::tag);
        return inSpan(builder.name(name).kind(Span.Kind.CONSUMER), requestId, work);
    }

    /**
     * Runs a delivery of a stored hand-off. Tracing on: a {@code PRODUCER} span named
     * {@code name} whose parent is the stored {@code traceparent} (a new trace when it is
     * missing or invalid). Tracing off: the stored ids, see {@link CorrelationContext#fromHeaders}.
     */
    public <T> T deliver(String name, String requestId, String traceparent, Map<String, String> tags,
                         Supplier<T> work) {
        if (!tracingEnabled) {
            return CorrelationContext.fromHeaders(requestId, traceparent).call(work);
        }
        Span.Builder builder = CorrelationContext.Traceparent.parse(traceparent)
                .map(parent -> childOf(parent.traceId(), parent.spanId(), parent.sampled()))
                .orElseGet(() -> tracer.spanBuilder().setNoParent());
        tags.forEach(builder::tag);
        return inSpan(builder.name(name).kind(Span.Kind.PRODUCER), requestId, work);
    }

    private Span.Builder childOf(String traceId, String spanId, boolean sampled) {
        TraceContext parent = tracer.traceContextBuilder().traceId(traceId).spanId(spanId).sampled(sampled).build();
        return tracer.spanBuilder().setParent(parent);
    }

    private <T> T inSpan(Span.Builder builder, String requestId, Supplier<T> work) {
        Span span = builder.start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return withIds(requestId, span.context()).call(work);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private Optional<Span> currentSpan() {
        return tracingEnabled ? Optional.ofNullable(tracer.currentSpan()) : Optional.empty();
    }

    private static CorrelationContext withIds(String requestId, TraceContext context) {
        return new CorrelationContext(
                CorrelationContext.validRequestId(requestId).orElseGet(CorrelationContext::newRequestId),
                context.traceId(), context.spanId());
    }
}
