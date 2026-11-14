package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.awesome.warehouse.client.FulfilmentState.AWAITING_PICK;
import static com.awesome.warehouse.client.FulfilmentState.CANCELLED;
import static com.awesome.warehouse.client.FulfilmentState.DELIVERED;
import static com.awesome.warehouse.client.FulfilmentState.NONE;
import static com.awesome.warehouse.client.FulfilmentState.PACKED;
import static com.awesome.warehouse.client.FulfilmentState.PICKED;
import static com.awesome.warehouse.client.FulfilmentState.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;

class FulfilmentAggregateTest {

    private static final List<ShipmentStatus> FORWARD = List.of(ShipmentStatus.AWAITING_PICK, ShipmentStatus.PICKED,
            ShipmentStatus.PACKED, ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED);
    /** The backend's only allowed forward step from each state (FakeBackend / FulfilmentState.nextStep). */
    private static final Map<FulfilmentState, FulfilmentState> NEXT = Map.of(NONE, AWAITING_PICK,
            AWAITING_PICK, PICKED, PICKED, PACKED, PACKED, SHIPPED, SHIPPED, DELIVERED);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''                                  | NONE",
            "AWAITING_PICK                       | AWAITING_PICK",
            "AWAITING_PICK,AWAITING_PICK         | AWAITING_PICK",
            "AWAITING_PICK,CANCELLED             | AWAITING_PICK",
            "PICKED,AWAITING_PICK                | PICKED",
            "SHIPPED,AWAITING_PICK               | PICKED",
            "PACKED,PICKED                       | PICKED",
            "PACKED,SHIPPED                      | PACKED",
            "SHIPPED,DELIVERED                   | SHIPPED",
            "DELIVERED,DELIVERED                 | DELIVERED",
            "DELIVERED,CANCELLED                 | DELIVERED",
            "CANCELLED,CANCELLED                 | CANCELLED",
            "PICKED                              | PICKED"})
    void aggregateExamples(String parcels, FulfilmentState expected) {
        List<ShipmentStatus> statuses = parcels.isBlank() ? List.of()
                : java.util.Arrays.stream(parcels.split(",")).map(ShipmentStatus::valueOf).toList();

        assertThat(FulfilmentAggregate.aggregate(statuses)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            ",AWAITING_PICK,AWAITING_PICK",
            "NONE,AWAITING_PICK,AWAITING_PICK",
            "PICKED,AWAITING_PICK,PICKED",
            "PICKED,PACKED,PACKED",
            "SHIPPED,PICKED,SHIPPED",
            "CANCELLED,PICKED,CANCELLED",
            "PICKED,CANCELLED,PICKED",
            ",CANCELLED,NONE",
            "DELIVERED,DELIVERED,DELIVERED"})
    void targetIsTheForwardMaximum(FulfilmentState last, FulfilmentState aggregate, FulfilmentState expected) {
        assertThat(FulfilmentAggregate.target(last, aggregate)).isEqualTo(expected);
    }

    @Test
    void stepsWalkTheBackendForwardOneStateAtATime() {
        assertThat(FulfilmentAggregate.steps(null, PICKED)).containsExactly(AWAITING_PICK, PICKED);
        assertThat(FulfilmentAggregate.steps(AWAITING_PICK, SHIPPED)).containsExactly(PICKED, PACKED, SHIPPED);
        assertThat(FulfilmentAggregate.steps(PICKED, PICKED)).isEmpty();
        assertThat(FulfilmentAggregate.steps(PACKED, PICKED)).isEmpty();
        assertThat(FulfilmentAggregate.steps(CANCELLED, PICKED)).isEmpty();
        assertThat(FulfilmentAggregate.steps(PICKED, CANCELLED)).isEmpty();
        assertThat(FulfilmentAggregate.steps(SHIPPED, DELIVERED)).containsExactly(DELIVERED);
    }

    @Test
    void eachParcelStatusStandsForTheSameNamedState() {
        for (ShipmentStatus status : ShipmentStatus.values()) {
            assertThat(FulfilmentAggregate.state(status).name()).isEqualTo(status.name());
        }
    }

    /** Properties of the aggregate over random parcel sets. */
    @Test
    void randomParcelSetsSatisfyTheAggregateProperties() {
        Random random = new Random(42L);
        for (int run = 0; run < 5_000; run++) {
            List<ShipmentStatus> parcels = randomParcels(random);
            List<ShipmentStatus> live = parcels.stream().filter(s -> s != ShipmentStatus.CANCELLED).toList();
            FulfilmentState aggregate = FulfilmentAggregate.aggregate(parcels);
            String context = "parcels " + parcels;

            if (parcels.isEmpty()) {
                assertThat(aggregate).as(context).isEqualTo(NONE);
                continue;
            }
            if (live.isEmpty()) {
                assertThat(aggregate).as(context).isEqualTo(CANCELLED);
                continue;
            }
            assertThat(aggregate).as(context).isNotIn(NONE, CANCELLED);
            // AWAITING_PICK exactly when every live parcel awaits pick.
            assertThat(aggregate == AWAITING_PICK).as(context)
                    .isEqualTo(live.stream().allMatch(s -> s == ShipmentStatus.AWAITING_PICK));
            // Never ahead of the slowest live parcel, except that picking any parcel shows as PICKED.
            int slowest = live.stream().mapToInt(FORWARD::indexOf).min().orElseThrow();
            int fastest = live.stream().mapToInt(FORWARD::indexOf).max().orElseThrow();
            int rank = FORWARD.indexOf(ShipmentStatus.valueOf(aggregate.name()));
            assertThat(rank).as(context).isGreaterThanOrEqualTo(slowest).isLessThanOrEqualTo(Math.max(slowest, 1))
                    .isLessThanOrEqualTo(fastest);
            // Order of parcels and extra cancelled parcels do not matter.
            List<ShipmentStatus> shuffled = new ArrayList<>(parcels);
            Collections.shuffle(shuffled, random);
            shuffled.add(ShipmentStatus.CANCELLED);
            assertThat(FulfilmentAggregate.aggregate(shuffled)).as(context).isEqualTo(aggregate);
            // Advancing one live parcel by one step never moves the aggregate back, and moves it at most one step.
            int index = random.nextInt(parcels.size());
            if (parcels.get(index) != ShipmentStatus.CANCELLED && parcels.get(index) != ShipmentStatus.DELIVERED) {
                List<ShipmentStatus> advanced = new ArrayList<>(parcels);
                advanced.set(index, FORWARD.get(FORWARD.indexOf(parcels.get(index)) + 1));
                int after = FulfilmentAggregate.FORWARD.indexOf(FulfilmentAggregate.aggregate(advanced));
                int before = FulfilmentAggregate.FORWARD.indexOf(aggregate);
                assertThat(after).as(context + " advancing " + index).isBetween(before, before + 1);
            }
            // The mirror target is the forward maximum and idempotent.
            FulfilmentState last = FulfilmentAggregate.FORWARD.get(random.nextInt(FulfilmentAggregate.FORWARD.size()));
            FulfilmentState target = FulfilmentAggregate.target(last, aggregate);
            assertThat(target).as(context).isIn(last, aggregate);
            assertThat(FulfilmentAggregate.FORWARD.indexOf(target)).as(context)
                    .isEqualTo(Math.max(FulfilmentAggregate.FORWARD.indexOf(last),
                            FulfilmentAggregate.FORWARD.indexOf(aggregate)));
            assertThat(FulfilmentAggregate.target(target, aggregate)).as(context).isEqualTo(target);
            assertStepsAreAcceptedByTheBackend(last, target, context);
        }
    }

    /**
     * Property: driving every parcel of a random split order from AWAITING_PICK to DELIVERED in a
     * random interleaving, the mirror only ever sends steps the backend accepts, the backend
     * is SHIPPED only once every parcel has shipped, and the order ends DELIVERED.
     */
    @Test
    void randomInterleavingsKeepTheBackendForwardOnlyAndEndDelivered() {
        Random random = new Random(7L);
        for (int run = 0; run < 2_000; run++) {
            List<ShipmentStatus> parcels = new ArrayList<>(
                    Collections.nCopies(1 + random.nextInt(4), ShipmentStatus.AWAITING_PICK));
            FulfilmentState backend = AWAITING_PICK;
            FulfilmentState mirrored = AWAITING_PICK;
            while (parcels.stream().anyMatch(s -> s != ShipmentStatus.DELIVERED)) {
                int index = random.nextInt(parcels.size());
                if (parcels.get(index) == ShipmentStatus.DELIVERED) {
                    continue;
                }
                parcels.set(index, FORWARD.get(FORWARD.indexOf(parcels.get(index)) + 1));
                FulfilmentState target = FulfilmentAggregate.target(mirrored, FulfilmentAggregate.aggregate(parcels));
                for (FulfilmentState step : FulfilmentAggregate.steps(mirrored, target)) {
                    assertThat(step).as("run " + run + " " + parcels).isEqualTo(NEXT.get(backend));
                    backend = step;
                }
                mirrored = target;
                boolean allShipped = parcels.stream().allMatch(s -> FORWARD.indexOf(s) >= 3);
                assertThat(backend == SHIPPED || backend == DELIVERED).as("run " + run + " " + parcels)
                        .isEqualTo(allShipped);
            }
            assertThat(backend).isEqualTo(DELIVERED);
        }
    }

    private static void assertStepsAreAcceptedByTheBackend(FulfilmentState from, FulfilmentState to, String context) {
        FulfilmentState backend = from;
        for (FulfilmentState step : FulfilmentAggregate.steps(from, to)) {
            assertThat(step).as(context).isEqualTo(NEXT.get(backend));
            backend = step;
        }
        assertThat(backend).as(context).isEqualTo(FulfilmentAggregate.FORWARD.indexOf(to)
                > FulfilmentAggregate.FORWARD.indexOf(from) ? to : from);
    }

    private static List<ShipmentStatus> randomParcels(Random random) {
        List<ShipmentStatus> parcels = new ArrayList<>();
        int count = random.nextInt(5);
        for (int i = 0; i < count; i++) {
            parcels.add(ShipmentStatus.values()[random.nextInt(ShipmentStatus.values().length)]);
        }
        return parcels;
    }
}
