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
 * First filter of every request: keeps a valid incoming {@code X-Request-Id} and the trace id
 * of a valid {@code traceparent} (generating whatever is missing or invalid), starts a new span,
 * puts {@code requestId}, {@code traceId} and {@code spanId} into the MDC for the request,
 * returns {@code X-Request-Id} and clears the MDC afterwards. An error dispatch of the same
 * request reuses its ids.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = CorrelationFilter.class.getName() + ".context";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationContext context = request.getAttribute(ATTRIBUTE) instanceof CorrelationContext existing
                ? existing
                : CorrelationContext.fromHeaders(request.getHeader(CorrelationContext.REQUEST_ID_HEADER),
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
