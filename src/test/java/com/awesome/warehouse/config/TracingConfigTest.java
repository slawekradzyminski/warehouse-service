package com.awesome.warehouse.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/** The runtime switch; the switched-on behaviour end to end is in {@code TracingFlowTest}. */
class TracingConfigTest {

    @Test
    void tracingOffSamplesNothingAndTracingOnFollowsTheParentOrTheProbability() {
        assertThat(TracingConfig.sampler(false, 1.0).getDescription()).isEqualTo("AlwaysOffSampler");
        assertThat(TracingConfig.sampler(true, 0.25).getDescription())
                .startsWith("ParentBased{root:TraceIdRatioBased{0.250000}");
    }

    @Test
    void theMdcIsLeftToTheCorrelationCode() {
        MDC.put("traceId", "kept");
        try {
            new TracingConfig().warehouseSlf4JEventListener().onEvent(new Object());
            assertThat(MDC.get("traceId")).isEqualTo("kept");
        } finally {
            MDC.clear();
        }
    }
}
