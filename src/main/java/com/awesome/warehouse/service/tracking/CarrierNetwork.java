package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.TrackingEventEntity;
import com.awesome.warehouse.entity.fulfilment.TrackingStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Deterministic scan texts: every carrier has its own sorting hub, and the
 * delivery city is derived from the tracking number, so a parcel always shows
 * the same route.
 */
final class CarrierNetwork {

    static final String WAREHOUSE_LOCATION = "Awesome Testing Warehouse, Krakow";
    static final List<String> CITIES = List.of("Warsaw", "Krakow", "Gdansk", "Wroclaw", "Poznan", "Lodz");

    private static final Map<Carrier, String> NAMES = Map.of(
            Carrier.INPOST, "InPost", Carrier.DPD, "DPD", Carrier.DHL, "DHL", Carrier.UPS, "UPS");
    private static final Map<Carrier, String> HUBS = Map.of(
            Carrier.INPOST, "InPost Sorting Centre, Pruszkow",
            Carrier.DPD, "DPD Hub, Strykow",
            Carrier.DHL, "DHL Hub, Leipzig",
            Carrier.UPS, "UPS Air Hub, Cologne");
    /** Nominal door-to-door days, used for the estimate of manually tracked parcels. */
    private static final Map<Carrier, Integer> TRANSIT_DAYS = Map.of(
            Carrier.INPOST, 1, Carrier.DPD, 2, Carrier.DHL, 2, Carrier.UPS, 3);

    private CarrierNetwork() {
    }

    static int transitDays(Carrier carrier) {
        return TRANSIT_DAYS.get(carrier);
    }

    static String city(String trackingNumber) {
        return CITIES.get(Math.floorMod(trackingNumber.hashCode(), CITIES.size()));
    }

    /** The scan with {@code status} that follows {@code history} for the simulated carrier. */
    static TrackingEventEntity scan(ShipmentEntity shipment, TrackingStatus status, List<TrackingStatus> history,
                                    LocalDateTime at) {
        String carrier = NAMES.get(shipment.getCarrier());
        String city = city(shipment.getTrackingNumber());
        String depot = carrier + " depot, " + city;
        return switch (status) {
            case LABEL_CREATED -> event(shipment, status, WAREHOUSE_LOCATION,
                    "Shipping label created; the parcel is waiting for " + carrier + " pickup", at);
            case IN_TRANSIT -> history.contains(TrackingStatus.IN_TRANSIT)
                    ? event(shipment, status, depot, "Arrived at the delivery depot", at)
                    : event(shipment, status, HUBS.get(shipment.getCarrier()),
                            "Collected from the sender and sorted at the hub", at);
            case OUT_FOR_DELIVERY -> event(shipment, status, depot,
                    history.contains(TrackingStatus.DELIVERY_FAILED)
                            ? "Out for delivery again (second attempt)"
                            : "Out for delivery with the courier", at);
            case DELIVERY_FAILED -> event(shipment, status, city,
                    "Delivery attempt failed: the recipient was not available. The courier will try again", at);
            case DELIVERED -> event(shipment, status, city, shipment.getCarrier() == Carrier.INPOST
                    ? "Delivered to the parcel locker" : "Delivered to the recipient", at);
        };
    }

    /** DELIVERED scan for a delivery confirmed manually by the warehouse. */
    static TrackingEventEntity confirmedDelivery(ShipmentEntity shipment, LocalDateTime at) {
        return event(shipment, TrackingStatus.DELIVERED, city(shipment.getTrackingNumber()),
                "Delivery confirmed by the warehouse", at);
    }

    private static TrackingEventEntity event(ShipmentEntity shipment, TrackingStatus status, String location,
                                             String description, LocalDateTime at) {
        return TrackingEventEntity.builder()
                .shipmentId(shipment.getId())
                .status(status)
                .location(location)
                .description(description)
                .occurredAt(at)
                .build();
    }
}
