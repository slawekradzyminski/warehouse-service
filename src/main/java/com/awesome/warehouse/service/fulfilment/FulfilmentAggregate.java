package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;

import java.util.Collection;
import java.util.List;

/**
 * The one fulfilment state the backend sees for an order shipped in several parcels
 * (pure: no I/O).
 *
 * <ul>
 *   <li>Aggregate: {@code AWAITING_PICK} while every non-cancelled parcel awaits pick,
 *       otherwise {@code max(PICKED, min over non-cancelled parcels)}; {@code CANCELLED}
 *       when every parcel is cancelled, {@code NONE} without parcels.</li>
 *   <li>Mirrored: {@code max(last mirrored, aggregate)}, so the backend only ever moves
 *       forward, even when a parcel is added or removed later.</li>
 *   <li>The backend accepts one forward step per call, so moving the mirror from one state
 *       to another may take several {@link #steps steps}.</li>
 * </ul>
 */
public final class FulfilmentAggregate {

    /** Forward order of the mirrored states; CANCELLED is outside it. */
    static final List<FulfilmentState> FORWARD = List.of(FulfilmentState.NONE, FulfilmentState.AWAITING_PICK,
            FulfilmentState.PICKED, FulfilmentState.PACKED, FulfilmentState.SHIPPED, FulfilmentState.DELIVERED);

    private FulfilmentAggregate() {
    }

    public static FulfilmentState aggregate(Collection<ShipmentStatus> parcels) {
        if (parcels.isEmpty()) {
            return FulfilmentState.NONE;
        }
        List<FulfilmentState> live = parcels.stream()
                .filter(status -> status != ShipmentStatus.CANCELLED)
                .map(FulfilmentAggregate::state)
                .toList();
        if (live.isEmpty()) {
            return FulfilmentState.CANCELLED;
        }
        if (live.stream().allMatch(state -> state == FulfilmentState.AWAITING_PICK)) {
            return FulfilmentState.AWAITING_PICK;
        }
        FulfilmentState slowest = live.stream().min(FulfilmentAggregate::compare).orElseThrow();
        return max(FulfilmentState.PICKED, slowest);
    }

    /**
     * The state to mirror: {@code max(lastMirrored, aggregate)}. A cancelled order stays
     * cancelled; an all-cancelled aggregate mirrors nothing (cancellation reaches the backend
     * through warehouse-cancel or the customer, never through the fulfilment endpoint).
     */
    public static FulfilmentState target(FulfilmentState lastMirrored, FulfilmentState aggregate) {
        FulfilmentState last = lastMirrored == null ? FulfilmentState.NONE : lastMirrored;
        if (last == FulfilmentState.CANCELLED || aggregate == FulfilmentState.CANCELLED) {
            return last;
        }
        return max(last, aggregate);
    }

    /** The fulfilment updates that move the backend from {@code from} to {@code to}, one step each. */
    public static List<FulfilmentState> steps(FulfilmentState from, FulfilmentState to) {
        FulfilmentState start = from == null ? FulfilmentState.NONE : from;
        if (start == FulfilmentState.CANCELLED || to == FulfilmentState.CANCELLED) {
            return List.of();
        }
        return FORWARD.subList(FORWARD.indexOf(start) + 1, Math.max(FORWARD.indexOf(start) + 1, FORWARD.indexOf(to) + 1));
    }

    /** The fulfilment state a single parcel status stands for. */
    public static FulfilmentState state(ShipmentStatus status) {
        return FulfilmentState.valueOf(status.name());
    }

    private static FulfilmentState max(FulfilmentState left, FulfilmentState right) {
        return compare(left, right) >= 0 ? left : right;
    }

    private static int compare(FulfilmentState left, FulfilmentState right) {
        return Integer.compare(FORWARD.indexOf(left), FORWARD.indexOf(right));
    }
}
