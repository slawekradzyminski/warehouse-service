package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.controller.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ShipmentReconcilerTest {

    private final ShipmentService shipmentService = mock(ShipmentService.class);
    private final LegacyShipmentLines legacyLines = mock(LegacyShipmentLines.class);

    @Test
    void theScheduledRunIsSkippedWhenDisabled() {
        new ShipmentReconciler(shipmentService, legacyLines, false).scheduledReconcile();

        verify(shipmentService, never()).reconcileCancellations();
    }

    @Test
    void eachScheduledRunHasItsOwnCorrelationIdsForBothSteps() {
        List<String> requestIds = new ArrayList<>();
        doAnswer(invocation -> recordRequestId(requestIds)).when(shipmentService).reconcileCancellations();
        doAnswer(invocation -> recordRequestId(requestIds)).when(shipmentService).adoptReleasedOrders();
        doAnswer(invocation -> recordRequestId(requestIds)).when(legacyLines).fillPendingBatch();
        ShipmentReconciler reconciler = new ShipmentReconciler(shipmentService, legacyLines, true);

        reconciler.scheduledReconcile();
        reconciler.scheduledReconcile();

        assertThat(requestIds).hasSize(6).doesNotContainNull();
        assertThat(requestIds.get(0)).isEqualTo(requestIds.get(1)).isEqualTo(requestIds.get(2))
                .isNotEqualTo(requestIds.get(3));
        assertThat(requestIds.get(3)).isEqualTo(requestIds.get(4)).isEqualTo(requestIds.get(5));
        assertThat(MDC.get("requestId")).isNull();
    }

    /** Answers an {@code int} job step after noting the request id it ran under. */
    private static int recordRequestId(List<String> requestIds) {
        requestIds.add(MDC.get("requestId"));
        return 0;
    }

    @Test
    void aBackendOutageOnlySkipsTheRun() {
        doThrow(new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE))
                .when(shipmentService).reconcileCancellations();

        new ShipmentReconciler(shipmentService, legacyLines, true).scheduledReconcile();

        verify(shipmentService, never()).adoptReleasedOrders();
        verify(legacyLines, never()).fillPendingBatch();
        assertThat(MDC.get("requestId")).isNull();
    }
}
