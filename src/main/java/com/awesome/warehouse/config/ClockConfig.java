package com.awesome.warehouse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Server-local clock used by return windows, purchase-order dates and
     * fulfilment timestamps, matching the server-local {@code LocalDateTime}
     * values exposed by the REST API (same convention as the backend).
     */
    @Bean
    Clock warehouseClock() {
        // PostgreSQL stores microseconds; ticking in micros keeps returned and reloaded timestamps equal.
        return Clock.tick(Clock.systemDefaultZone(), Duration.ofNanos(1_000));
    }
}
