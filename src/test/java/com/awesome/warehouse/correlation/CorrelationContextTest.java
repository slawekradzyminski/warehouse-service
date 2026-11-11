package com.awesome.warehouse.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "req-1.retry_2", "ABCxyz0123456789"})
    void validRequestIdsAreKept(String requestId) {
        assertThat(CorrelationContext.fromHeaders(requestId, null).requestId()).isEqualTo(requestId);
    }

    @Test
    void aRequestIdOf128CharactersIsKeptAndOneOf129IsReplaced() {
        String longest = "x".repeat(128);

        assertThat(CorrelationContext.fromHeaders(longest, null).requestId()).isEqualTo(longest);
        assertThat(CorrelationContext.fromHeaders(longest + "x", null).requestId()).matches(UUID_PATTERN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"with space", "semi;colon", "new\nline", "ünicode", "a/b"})
    void invalidRequestIdsAreReplacedWithAUuid(String requestId) {
        assertThat(CorrelationContext.fromHeaders(requestId, null).requestId()).matches(UUID_PATTERN);
    }

    @Test
    void theTraceIdOfAValidTraceparentIsKeptWithANewSpan() {
        CorrelationContext context = CorrelationContext.fromHeaders("r", TRACEPARENT);

        assertThat(context.traceId()).isEqualTo(TRACE_ID);
        assertThat(context.spanId()).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");
        assertThat(CorrelationContext.traceIdOf("00-" + TRACE_ID + "-00f067aa0ba902b7-00")).contains(TRACE_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
        "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
        "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
        "garbage"})
    void invalidTraceparentsGetANewTrace(String traceparent) {
        assertThat(CorrelationContext.traceIdOf(traceparent)).isEmpty();
        assertThat(CorrelationContext.fromHeaders("r", traceparent).traceId())
                .matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
    }

    @Test
    void feedEventIdsAreValidatedLikeHeaders() {
        CorrelationContext kept = CorrelationContext.fromIds("req-1", TRACE_ID);
        CorrelationContext replaced = CorrelationContext.fromIds("bad id", "0".repeat(32));

        assertThat(kept.requestId()).isEqualTo("req-1");
        assertThat(kept.traceId()).isEqualTo(TRACE_ID);
        assertThat(replaced.requestId()).matches(UUID_PATTERN);
        assertThat(replaced.traceId()).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        assertThat(CorrelationContext.validTraceId(null)).isEmpty();
        assertThat(CorrelationContext.validTraceId(TRACE_ID.toUpperCase(java.util.Locale.ROOT))).isEmpty();
    }

    @Test
    void generatedIdsAreWellFormedAndUnique() {
        CorrelationContext first = CorrelationContext.generate();
        CorrelationContext second = CorrelationContext.generate();

        assertThat(first.requestId()).matches(UUID_PATTERN).isNotEqualTo(second.requestId());
        assertThat(first.traceId()).matches("[0-9a-f]{32}").isNotEqualTo(second.traceId());
        assertThat(first.spanId()).matches("[0-9a-f]{16}").isNotEqualTo(second.spanId());
        assertThat(CorrelationContext.traceIdOf(first.traceparent())).contains(first.traceId());
    }

    @Test
    void traceparentsCarryTheTraceIdAndAChildGetsANewParent() {
        CorrelationContext context = new CorrelationContext("r", TRACE_ID, "00f067aa0ba902b7");

        assertThat(context.traceparent()).isEqualTo(TRACEPARENT);
        assertThat(context.childTraceparent())
                .matches("00-" + TRACE_ID + "-[0-9a-f]{16}-01")
                .isNotEqualTo(TRACEPARENT)
                .isNotEqualTo(context.childTraceparent());
    }

    @Test
    void openPutsTheIdsIntoTheMdcAndCloseRestoresThePreviousOnes() {
        CorrelationContext outer = new CorrelationContext("outer", TRACE_ID, "00f067aa0ba902b7");
        CorrelationContext inner = CorrelationContext.generate();

        try (CorrelationContext.Scope ignored = outer.open()) {
            assertThat(CorrelationContext.current()).contains(outer);
            inner.run(() -> {
                assertThat(MDC.get("requestId")).isEqualTo(inner.requestId());
                assertThat(MDC.get("traceId")).isEqualTo(inner.traceId());
                assertThat(MDC.get("spanId")).isEqualTo(inner.spanId());
            });
            assertThat(CorrelationContext.current()).contains(outer);
        }

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
        assertThat(CorrelationContext.current()).isEmpty();
    }

    @Test
    void callReturnsTheResultAndRestoresTheMdcEvenWhenTheWorkFails() {
        CorrelationContext context = CorrelationContext.generate();

        assertThat(context.call(() -> MDC.get("requestId"))).isEqualTo(context.requestId());
        try {
            context.run(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void currentNeedsRequestIdAndTraceIdAndFillsAMissingSpan() {
        MDC.put("requestId", "r");
        assertThat(CorrelationContext.current()).isEmpty();

        MDC.put("traceId", TRACE_ID);
        Optional<CorrelationContext> withoutSpan = CorrelationContext.current();
        assertThat(withoutSpan).hasValueSatisfying(context -> {
            assertThat(context.requestId()).isEqualTo("r");
            assertThat(context.traceId()).isEqualTo(TRACE_ID);
            assertThat(context.spanId()).matches("[0-9a-f]{16}");
        });

        MDC.remove("requestId");
        assertThat(CorrelationContext.current()).isEmpty();
    }
}
