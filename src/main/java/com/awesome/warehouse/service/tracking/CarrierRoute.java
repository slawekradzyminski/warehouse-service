package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.entity.fulfilment.TrackingStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The simulated carrier's route: LABEL_CREATED, IN_TRANSIT (sorting hub),
 * IN_TRANSIT (delivery depot), OUT_FOR_DELIVERY, DELIVERED. A parcel whose
 * tracking number satisfies {@link #failsFirstAttempt(String)} fails its first
 * delivery attempt (DELIVERY_FAILED) and goes OUT_FOR_DELIVERY again.
 */
final class CarrierRoute {

    /** IN_TRANSIT scans before the parcel goes out for delivery: sorting hub, then delivery depot. */
    static final int TRANSIT_SCANS = 2;

    private CarrierRoute() {
    }

    /** Deterministic failure rule from the contract. */
    static boolean failsFirstAttempt(String trackingNumber) {
        return Math.abs(trackingNumber.hashCode()) % 5 == 0;
    }

    /** The status of the next scan after {@code history} (oldest first). */
    static TrackingStatus next(List<TrackingStatus> history, boolean failsFirstAttempt) {
        if (history.isEmpty()) {
            return TrackingStatus.LABEL_CREATED;
        }
        return switch (history.getLast()) {
            case LABEL_CREATED -> TrackingStatus.IN_TRANSIT;
            case IN_TRANSIT -> count(history, TrackingStatus.IN_TRANSIT) < TRANSIT_SCANS
                    ? TrackingStatus.IN_TRANSIT : TrackingStatus.OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY -> failsFirstAttempt && !history.contains(TrackingStatus.DELIVERY_FAILED)
                    ? TrackingStatus.DELIVERY_FAILED : TrackingStatus.DELIVERED;
            case DELIVERY_FAILED -> TrackingStatus.OUT_FOR_DELIVERY;
            case DELIVERED -> throw new IllegalStateException("A delivered parcel has no next scan");
        };
    }

    /** Scans still needed until DELIVERED; 0 once delivered. */
    static int remainingSteps(List<TrackingStatus> history, boolean failsFirstAttempt) {
        List<TrackingStatus> route = new ArrayList<>(history);
        int steps = 0;
        while (route.isEmpty() || route.getLast() != TrackingStatus.DELIVERED) {
            route.add(next(route, failsFirstAttempt));
            steps++;
        }
        return steps;
    }

    static long count(List<TrackingStatus> history, TrackingStatus status) {
        return history.stream().filter(status::equals).count();
    }
}
