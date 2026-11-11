package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.controller.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CarrierSimulatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final TrackingService trackingService = mock(TrackingService.class);

    @Test
    void theScheduledStepRunsOnlyWhenEnabled() {
        when(trackingService.idleSimulatedShipments(NOW)).thenReturn(List.of(1L));
        when(trackingService.advanceIfIdle(1L, NOW)).thenReturn(true);

        new CarrierSimulator(trackingService, CLOCK, false).scheduledStep();
        verify(trackingService, never()).idleSimulatedShipments(any());
        new CarrierSimulator(trackingService, CLOCK, true).scheduledStep();
        verify(trackingService).advanceIfIdle(1L, NOW);
    }

    @Test
    void eachScheduledStepRunsWithItsOwnGeneratedCorrelationIds() {
        java.util.List<String> requestIds = new java.util.ArrayList<>();
        when(trackingService.idleSimulatedShipments(NOW)).thenAnswer(invocation -> {
            requestIds.add(org.slf4j.MDC.get("requestId"));
            assertThat(org.slf4j.MDC.get("traceId")).matches("[0-9a-f]{32}");
            return List.of();
        });
        CarrierSimulator simulator = new CarrierSimulator(trackingService, CLOCK, true);

        simulator.scheduledStep();
        simulator.scheduledStep();

        assertThat(requestIds).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }

    @Test
    void onlyShipmentsThatAdvancedAreCountedAndOtherFailuresDoNotStopTheRun() {
        when(trackingService.idleSimulatedShipments(NOW)).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(trackingService.advanceIfIdle(1L, NOW)).thenReturn(true);
        when(trackingService.advanceIfIdle(2L, NOW)).thenReturn(false);
        when(trackingService.advanceIfIdle(3L, NOW)).thenThrow(new CustomException("conflict", HttpStatus.CONFLICT));
        when(trackingService.advanceIfIdle(4L, NOW)).thenReturn(true);

        assertThat(new CarrierSimulator(trackingService, CLOCK, true).advanceIdle(NOW)).isEqualTo(2);
    }

    @Test
    void aBackendOutageEndsTheRun() {
        when(trackingService.idleSimulatedShipments(NOW)).thenReturn(List.of(1L, 2L, 3L));
        when(trackingService.advanceIfIdle(1L, NOW)).thenReturn(true);
        when(trackingService.advanceIfIdle(2L, NOW))
                .thenThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(new CarrierSimulator(trackingService, CLOCK, true).advanceIdle(NOW)).isEqualTo(1);
        verify(trackingService, never()).advanceIfIdle(3L, NOW);
    }
}
