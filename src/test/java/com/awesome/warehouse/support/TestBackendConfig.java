package com.awesome.warehouse.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
/** Replaces the HTTP backend client with {@link FakeBackend} (the only primary {@code BackendClient}). */
public class TestBackendConfig {

    @Bean
    @Primary
    FakeBackend fakeBackend() {
        return new FakeBackend();
    }
}
