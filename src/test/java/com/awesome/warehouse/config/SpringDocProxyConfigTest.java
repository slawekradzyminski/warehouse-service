package com.awesome.warehouse.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringDocProxyConfigTest {

    @Test
    void productionGatewayAdvertisesHttpsWithoutTheDefaultPort() {
        assertThat(url("http://warehouse-service:4003/v3/api-docs",
                "awesome.byst.re", "awesome.byst.re", "443", "https")).isEqualTo("https://awesome.byst.re");
    }

    @Test
    void localGatewayKeepsThePublishedPortAlthoughItForwardsItsContainerPort() {
        assertThat(url("http://warehouse-service:4003/v3/api-docs",
                "localhost:8081", "localhost:8081", "80", "http")).isEqualTo("http://localhost:8081");
    }

    @Test
    void directCallsFallBackToTheRequestUrl() {
        assertThat(url("http://localhost:4003/v3/api-docs", null, null, null, null))
                .isEqualTo("http://localhost:4003");
        assertThat(url("http://localhost:80/v3/api-docs", null, null, null, null)).isEqualTo("http://localhost");
    }

    @Test
    void ipv6HostsKeepTheirBrackets() {
        assertThat(url("http://warehouse-service:4003/v3/api-docs", "[::1]:8081", null, null, "http"))
                .isEqualTo("http://[::1]:8081");
    }

    private static String url(String uri, String host, String forwardedHost, String forwardedPort, String proto) {
        HttpHeaders headers = new HttpHeaders();
        if (host != null) {
            headers.add(HttpHeaders.HOST, host);
        }
        if (forwardedHost != null) {
            headers.add("X-Forwarded-Host", forwardedHost);
        }
        if (forwardedPort != null) {
            headers.add("X-Forwarded-Port", forwardedPort);
        }
        if (proto != null) {
            headers.add("X-Forwarded-Proto", proto);
        }
        HttpRequest request = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return HttpMethod.GET;
            }

            @Override
            public URI getURI() {
                return URI.create(uri);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Map.of();
            }
        };
        return new SpringDocProxyConfig().serverBaseUrlCustomizer().customize("http://fallback", request);
    }
}
