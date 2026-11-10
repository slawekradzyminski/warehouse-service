package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.entity.fulfilment.TrackingStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.DELIVERED;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.DELIVERY_FAILED;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.IN_TRANSIT;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.LABEL_CREATED;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarrierRouteTest {

    @Test
    void theFailureRuleIsAbsHashCodeModuloFive() {
        assertThat(CarrierRoute.failsFirstAttempt("1234567894")).isTrue();   // hashCode -2054162785
        assertThat(CarrierRoute.failsFirstAttempt("1234567899")).isTrue();   // hashCode -2054162780
        assertThat(CarrierRoute.failsFirstAttempt("0000000000")).isTrue();   // hashCode 1419845120
        assertThat(CarrierRoute.failsFirstAttempt("1234567890")).isFalse();  // hashCode -2054162789
        assertThat(CarrierRoute.failsFirstAttempt("1234567893")).isFalse();  // hashCode -2054162786
    }

    @Test
    void aParcelDeliveredFirstTimeTakesFourStepsAfterTheLabel() {
        assertThat(walk(false)).containsExactly(LABEL_CREATED, IN_TRANSIT, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED);
    }

    @Test
    void aFailingParcelIsRetriedOnceAfterTheFailedAttempt() {
        assertThat(walk(true)).containsExactly(LABEL_CREATED, IN_TRANSIT, IN_TRANSIT, OUT_FOR_DELIVERY,
                DELIVERY_FAILED, OUT_FOR_DELIVERY, DELIVERED);
    }

    @Test
    void remainingStepsCountsTheScansUntilDelivery() {
        assertThat(CarrierRoute.remainingSteps(List.of(), false)).isEqualTo(5);
        assertThat(CarrierRoute.remainingSteps(List.of(LABEL_CREATED), false)).isEqualTo(4);
        assertThat(CarrierRoute.remainingSteps(List.of(LABEL_CREATED), true)).isEqualTo(6);
        assertThat(CarrierRoute.remainingSteps(List.of(LABEL_CREATED, IN_TRANSIT, IN_TRANSIT, OUT_FOR_DELIVERY,
                DELIVERY_FAILED), true)).isEqualTo(2);
        assertThat(CarrierRoute.remainingSteps(List.of(LABEL_CREATED, DELIVERED), false)).isZero();
    }

    @Test
    void aDeliveredParcelHasNoNextScan() {
        assertThatThrownBy(() -> CarrierRoute.next(List.of(LABEL_CREATED, DELIVERED), false))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<TrackingStatus> walk(boolean fails) {
        java.util.ArrayList<TrackingStatus> route = new java.util.ArrayList<>();
        do {
            route.add(CarrierRoute.next(route, fails));
        } while (route.getLast() != DELIVERED);
        return route;
    }
}
