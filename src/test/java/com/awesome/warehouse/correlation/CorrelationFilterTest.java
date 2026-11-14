package com.awesome.warehouse.correlation;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final CorrelationFilter filter = new CorrelationFilter(TracingSupport.disabled());
    private final Map<String, String> seen = new HashMap<>();
    private final MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
    }) {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            seen.putAll(MDC.getCopyOfContextMap());
        }
    };

    @Test
    void runsRightAfterTheServerObservationFilterAndBeforeEveryOtherFilter() {
        // Spring Boot registers ServerHttpObservationFilter at HIGHEST_PRECEDENCE + 1; Spring Security is far later.
        assertThat(CorrelationFilter.class.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2)
                .isLessThan(org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    void validIncomingIdsAreUsedForTheRequestAndReturned() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/warehouse/queue");
        request.addHeader("X-Request-Id", "req-1");
        request.addHeader("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-1");
        assertThat(seen).containsEntry("requestId", "req-1").containsEntry("traceId", TRACE_ID);
        assertThat(seen.get("spanId")).matches("[0-9a-f]{16}").isNotEqualTo("00f067aa0ba902b7");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void missingOrInvalidIdsAreGenerated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "x".repeat(129));
        request.addHeader("traceparent", "00-" + TRACE_ID + "-0000000000000000-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).hasSize(36).isEqualTo(seen.get("requestId"));
        assertThat(seen.get("traceId")).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
    }

    @Test
    void theMdcIsClearedWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .hasMessage("boom");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void anErrorDispatchKeepsTheIdsOfTheOriginalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/warehouse/queue");
        request.addHeader("X-Request-Id", "req-1");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        String traceId = seen.get("traceId");
        seen.clear();

        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/warehouse/queue");
        request.removeAttribute(CorrelationFilter.class.getName() + ".FILTERED");
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        filter.doFilter(request, errorResponse, chain);

        assertThat(seen).containsEntry("requestId", "req-1").containsEntry("traceId", traceId);
        assertThat(errorResponse.getHeader("X-Request-Id")).isEqualTo("req-1");
        assertThat(request.getAttribute(CorrelationFilter.ATTRIBUTE)).isInstanceOf(CorrelationContext.class);
    }
}
