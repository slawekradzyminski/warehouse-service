package com.awesome.warehouse.correlation;

import org.slf4j.MDC;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The correlation ids of one unit of work: the {@code X-Request-Id} value, the W3C trace id
 * and this hop's span id. Incoming values are validated and replaced when invalid (see the
 * "Correlation IDs" contract); {@link #open()} puts them into the logging MDC under
 * {@link #MDC_REQUEST_ID}, {@link #MDC_TRACE_ID} and {@link #MDC_SPAN_ID}.
 */
public record CorrelationContext(String requestId, String traceId, String spanId) {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);
    private static final String SAMPLED_FLAGS = "01";

    /** Fresh ids for work that has no caller, such as one run of a scheduled job. */
    public static CorrelationContext generate() {
        return new CorrelationContext(newRequestId(), newTraceId(), newSpanId());
    }

    /**
     * Ids of an incoming request (or of a stored hand-off): a valid request id and the
     * trace id of a valid {@code traceparent} are kept, anything else is generated. The
     * span id is always new, because this service is a new hop.
     */
    public static CorrelationContext fromHeaders(String requestId, String traceparent) {
        return new CorrelationContext(validRequestId(requestId).orElseGet(CorrelationContext::newRequestId),
                traceIdOf(traceparent).orElseGet(CorrelationContext::newTraceId), newSpanId());
    }

    /** Ids carried by a feed event (a bare 32-hex trace id rather than a {@code traceparent}). */
    public static CorrelationContext fromIds(String requestId, String traceId) {
        return new CorrelationContext(validRequestId(requestId).orElseGet(CorrelationContext::newRequestId),
                validTraceId(traceId).orElseGet(CorrelationContext::newTraceId), newSpanId());
    }

    /** The ids in the MDC of the current thread, if a request or job has set them. */
    public static Optional<CorrelationContext> current() {
        String requestId = MDC.get(MDC_REQUEST_ID);
        String traceId = MDC.get(MDC_TRACE_ID);
        if (requestId == null || traceId == null) {
            return Optional.empty();
        }
        String spanId = MDC.get(MDC_SPAN_ID);
        return Optional.of(new CorrelationContext(requestId, traceId, spanId == null ? newSpanId() : spanId));
    }

    /** The {@code traceparent} of this hop (stored with hand-offs such as the notification outbox). */
    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-" + SAMPLED_FLAGS;
    }

    /** A {@code traceparent} for an outbound call: same trace id, new parent id. */
    public String childTraceparent() {
        return "00-" + traceId + "-" + newSpanId() + "-" + SAMPLED_FLAGS;
    }

    /** Puts these ids into the MDC; closing the scope restores what was there before. */
    public Scope open() {
        Scope scope = new Scope(MDC.get(MDC_REQUEST_ID), MDC.get(MDC_TRACE_ID), MDC.get(MDC_SPAN_ID));
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        return scope;
    }

    /** Runs {@code work} with these ids in the MDC. */
    public void run(Runnable work) {
        try (Scope ignored = open()) {
            work.run();
        }
    }

    /** Calls {@code work} with these ids in the MDC. */
    public <T> T call(Supplier<T> work) {
        try (Scope ignored = open()) {
            return work.get();
        }
    }

    public static Optional<String> validRequestId(String value) {
        return value != null && REQUEST_ID.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }

    public static Optional<String> validTraceId(String value) {
        return value != null && TRACE_ID.matcher(value).matches() && !ZERO_TRACE_ID.equals(value)
                ? Optional.of(value) : Optional.empty();
    }

    /** A non-zero 16-hex span id (the parent id of a {@code traceparent}). */
    public static Optional<String> validSpanId(String value) {
        return value != null && SPAN_ID.matcher(value).matches() && !ZERO_SPAN_ID.equals(value)
                ? Optional.of(value) : Optional.empty();
    }

    /** The trace id of a valid W3C {@code traceparent} (version 00, non-zero trace and parent ids). */
    public static Optional<String> traceIdOf(String traceparent) {
        return Traceparent.parse(traceparent).map(Traceparent::traceId);
    }

    /** The parts of a valid W3C {@code traceparent}: trace id, parent (span) id and the sampled flag. */
    public record Traceparent(String traceId, String spanId, boolean sampled) {

        /** Version 00 with non-zero trace and parent ids; anything else is empty. */
        public static Optional<Traceparent> parse(String traceparent) {
            if (traceparent == null) {
                return Optional.empty();
            }
            Matcher matcher = TRACEPARENT.matcher(traceparent);
            if (!matcher.matches() || validSpanId(matcher.group(2)).isEmpty()
                    || validTraceId(matcher.group(1)).isEmpty()) {
                return Optional.empty();
            }
            boolean sampled = (Integer.parseInt(matcher.group(3), 16) & 1) == 1;
            return Optional.of(new Traceparent(matcher.group(1), matcher.group(2), sampled));
        }

        /** The header value, for example {@code 00-<trace id>-<span id>-01}. */
        public String value() {
            return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
        }
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public static String newTraceId() {
        String traceId = hex(ThreadLocalRandom.current().nextLong()) + hex(ThreadLocalRandom.current().nextLong());
        return ZERO_TRACE_ID.equals(traceId) ? newTraceId() : traceId;
    }

    public static String newSpanId() {
        long value = ThreadLocalRandom.current().nextLong();
        return value == 0 ? newSpanId() : hex(value);
    }

    private static String hex(long value) {
        return String.format(Locale.ROOT, "%016x", value);
    }

    /** Restores the MDC values that were present before {@link #open()}. */
    public static final class Scope implements AutoCloseable {

        private final String requestId;
        private final String traceId;
        private final String spanId;

        private Scope(String requestId, String traceId, String spanId) {
            this.requestId = requestId;
            this.traceId = traceId;
            this.spanId = spanId;
        }

        @Override
        public void close() {
            restore(MDC_REQUEST_ID, requestId);
            restore(MDC_TRACE_ID, traceId);
            restore(MDC_SPAN_ID, spanId);
        }

        private static void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
