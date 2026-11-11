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

    @Test
    void theScheduledRunIsSkippedWhenDisabled() {
        new ShipmentReconciler(shipmentService, false).scheduledReconcile();

        verify(shipmentService, never()).reconcileCancellations();
    }

    @Test
    void eachScheduledRunHasItsOwnCorrelationIdsForBothSteps() {
        List<String> requestIds = new ArrayList<>();
        doAnswer(invocation -> recordRequestId(requestIds)).when(shipmentService).reconcileCancellations();
        doAnswer(invocation -> recordRequestId(requestIds)).when(shipmentService).adoptReleasedOrders();
        ShipmentReconciler reconciler = new ShipmentReconciler(shipmentService, true);

        reconciler.scheduledReconcile();
        reconciler.scheduledReconcile();

        assertThat(requestIds).hasSize(4).doesNotContainNull();
        assertThat(requestIds.get(0)).isEqualTo(requestIds.get(1)).isNotEqualTo(requestIds.get(2));
        assertThat(requestIds.get(2)).isEqualTo(requestIds.get(3));
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

        new ShipmentReconciler(shipmentService, true).scheduledReconcile();

        verify(shipmentService, never()).adoptReleasedOrders();
        assertThat(MDC.get("requestId")).isNull();
    }
}
