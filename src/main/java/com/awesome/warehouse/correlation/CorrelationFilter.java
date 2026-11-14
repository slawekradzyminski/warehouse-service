package com.awesome.warehouse.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Early filter of every request: keeps a valid incoming {@code X-Request-Id} and the trace id
 * of a valid {@code traceparent} (generating whatever is missing or invalid), starts a new span,
 * puts {@code requestId}, {@code traceId} and {@code spanId} into the MDC for the request,
 * returns {@code X-Request-Id} and clears the MDC afterwards. An error dispatch of the same
 * request reuses its ids.
 *
 * <p>It runs right after Spring's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}),
 * so with tracing on the server span, already parented by the incoming {@code traceparent},
 * supplies the trace and span id (see {@link TracingSupport#forRequest}).
 */
@Component
@Order(CorrelationFilter.ORDER)
public class CorrelationFilter extends OncePerRequestFilter {

    /** Right after the server observation filter, before Spring Security and everything else. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;
    static final String ATTRIBUTE = CorrelationFilter.class.getName() + ".context";

    private final TracingSupport tracing;

    public CorrelationFilter(TracingSupport tracing) {
        super();
        this.tracing = tracing;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationContext context = request.getAttribute(ATTRIBUTE) instanceof CorrelationContext existing
                ? existing
                : tracing.forRequest(request.getHeader(CorrelationContext.REQUEST_ID_HEADER),
                        request.getHeader(CorrelationContext.TRACEPARENT_HEADER));
        request.setAttribute(ATTRIBUTE, context);
        response.setHeader(CorrelationContext.REQUEST_ID_HEADER, context.requestId());
        try (CorrelationContext.Scope ignored = context.open()) {
            chain.doFilter(request, response);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
