package com.awesome.warehouse.config;

import org.springdoc.core.customizers.ServerBaseUrlCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;

/**
 * Builds the OpenAPI server URL from the gateway's X-Forwarded-* headers, so the document
 * advertises https://awesome.byst.re behind the TLS edge instead of the plain-HTTP hop to
 * the container. The native image cannot rely on server.forward-headers-strategy: its
 * ForwardedHeaderFilter condition is fixed at build time. Same logic as the backend.
 */
@Configuration(proxyBeanMethods = false)
public class SpringDocProxyConfig {

    @Bean
    ServerBaseUrlCustomizer serverBaseUrlCustomizer() {
        return SpringDocProxyConfig::serverBaseUrl;
    }

    static String serverBaseUrl(String serverBaseUrl, HttpRequest request) {
        String scheme = firstHeader(request, "X-Forwarded-Proto");
        if (blank(scheme)) {
            scheme = request.getURI().getScheme();
        }
        String hostWithPort = firstHeader(request, "X-Forwarded-Host");
        if (blank(hostWithPort)) {
            hostWithPort = firstHeader(request, HttpHeaders.HOST);
        }
        if (blank(hostWithPort)) {
            hostWithPort = request.getURI().getHost();
        }
        if (blank(hostWithPort)) {
            return serverBaseUrl;
        }
        String host = hostWithPort;
        String port = firstHeader(request, "X-Forwarded-Port");
        if (hostWithPort.startsWith("[")) {
            int closing = hostWithPort.indexOf(']');
            if (closing > -1) {
                host = hostWithPort.substring(0, closing + 1);
                int separator = hostWithPort.indexOf(':', closing);
                if (blank(port) && separator > -1 && separator + 1 < hostWithPort.length()) {
                    port = hostWithPort.substring(separator + 1);
                }
            }
        } else if (hostWithPort.contains(":")) {
            String[] parts = hostWithPort.split(":", 2);
            host = parts[0];
            if (blank(port)) {
                port = parts[1];
            }
        }
        if (blank(port) && request.getURI().getPort() > 0) {
            port = Integer.toString(request.getURI().getPort());
        }
        if (blank(port) || isDefaultPort(scheme, port)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static boolean isDefaultPort(String scheme, String port) {
        return ("http".equalsIgnoreCase(scheme) && "80".equals(port))
                || ("https".equalsIgnoreCase(scheme) && "443".equals(port));
    }

    private static String firstHeader(HttpRequest request, String name) {
        return request.getHeaders().getFirst(name);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
