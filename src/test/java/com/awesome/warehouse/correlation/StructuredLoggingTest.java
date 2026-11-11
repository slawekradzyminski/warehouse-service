package com.awesome.warehouse.correlation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs} (what the server Compose sets) turns every line
 * into one ECS JSON object in which the MDC ids are fields.
 */
class StructuredLoggingTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void ecsLinesCarryTheCorrelationIdsAsFields() {
        LoggerContext loggerContext = new LoggerContext();
        loggerContext.setMDCAdapter(MDC.getMDCAdapter());
        loggerContext.putObject(Environment.class.getName(),
                new MockEnvironment().withProperty("spring.application.name", "warehouse-service"));
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggerContext);
        encoder.setFormat("ecs");
        encoder.start();
        CorrelationContext context = new CorrelationContext("req-1", "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7");

        String line = context.call(() -> {
            LoggingEvent event = new LoggingEvent("test", loggerContext.getLogger("test"), Level.INFO,
                    "Handled event", null, null);
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        });

        JsonNode json = JsonMapper.builder().build().readTree(line);
        assertThat(json.get("message").asString()).isEqualTo("Handled event");
        assertThat(json.get("requestId").asString()).isEqualTo("req-1");
        assertThat(json.get("traceId").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(json.get("spanId").asString()).isEqualTo("00f067aa0ba902b7");
        assertThat(json.at("/service/name").asString()).isEqualTo("warehouse-service");
    }
}
