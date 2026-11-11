package com.awesome.warehouse.correlation;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Sends the current {@code X-Request-Id} and a child {@code traceparent} (same trace id, new
 * parent id) with every outbound call. Without ids in the MDC (which the filter, the
 * scheduled jobs and the event feed always set) the call gets fresh ones.
 */
public class CorrelationInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        CorrelationContext context = CorrelationContext.current().orElseGet(CorrelationContext::generate);
        request.getHeaders().set(CorrelationContext.REQUEST_ID_HEADER, context.requestId());
        request.getHeaders().set(CorrelationContext.TRACEPARENT_HEADER, context.childTraceparent());
        return execution.execute(request, body);
    }
}
