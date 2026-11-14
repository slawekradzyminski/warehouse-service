package com.awesome.warehouse.config;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.RestBackendClient;
import com.awesome.warehouse.correlation.CorrelationInterceptor;
import com.awesome.warehouse.correlation.TracingSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class BackendClientConfig {

    /** Shared JDK HTTP client for the internal API; closed with the application context. */
    @Bean(destroyMethod = "close")
    HttpClient backendHttpClient(@Value("${backend.connect-timeout:2s}") Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    BackendClient backendClient(
            RestClient.Builder builder,
            HttpClient backendHttpClient,
            @Value("${backend.base-url}") String baseUrl,
            @Value("${warehouse.service-token}") String serviceToken,
            @Value("${backend.read-timeout:10s}") Duration readTimeout,
            TracingSupport tracing) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(backendHttpClient);
        requestFactory.setReadTimeout(readTimeout);
        // RestClient.Builder is Boot's observed builder: with tracing on it sends the client span's traceparent.
        return new RestBackendClient(builder.requestFactory(requestFactory), baseUrl, serviceToken,
                new CorrelationInterceptor(tracing.enabled()));
    }
}
