package com.awesome.warehouse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Server-local clock used by return windows, purchase-order dates and
     * fulfilment timestamps, matching the server-local {@code LocalDateTime}
     * values exposed by the REST API (same convention as the backend).
     */
    @Bean
    Clock warehouseClock() {
        return Clock.systemDefaultZone();
    }
}
