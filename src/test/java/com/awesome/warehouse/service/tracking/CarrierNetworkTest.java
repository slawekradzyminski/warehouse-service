package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.TrackingEventEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;

import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.DELIVERED;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.DELIVERY_FAILED;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.IN_TRANSIT;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.LABEL_CREATED;
import static com.awesome.warehouse.entity.fulfilment.TrackingStatus.OUT_FOR_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;

class CarrierNetworkTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 10, 9, 30);

    @Test
    void scansFollowTheCarrierRouteWithDeterministicTexts() {
        ShipmentEntity dhl = shipment(Carrier.DHL, "1234567890");

        assertThat(CarrierNetwork.scan(dhl, LABEL_CREATED, List.of(), AT)).satisfies(e -> {
            assertThat(e.getShipmentId()).isEqualTo(7L);
            assertThat(e.getStatus()).isEqualTo(LABEL_CREATED);
            assertThat(e.getOccurredAt()).isEqualTo(AT);
            assertThat(e.getLocation()).isEqualTo("Awesome Testing Warehouse, Krakow");
            assertThat(e.getDescription()).isEqualTo("Shipping label created; the parcel is waiting for DHL pickup");
        });
        assertText(CarrierNetwork.scan(dhl, IN_TRANSIT, List.of(LABEL_CREATED), AT),
                "DHL Hub, Leipzig", "Collected from the sender and sorted at the hub");
        assertText(CarrierNetwork.scan(dhl, IN_TRANSIT, List.of(LABEL_CREATED, IN_TRANSIT), AT),
                "DHL depot, Krakow", "Arrived at the delivery depot");
        assertText(CarrierNetwork.scan(dhl, OUT_FOR_DELIVERY, List.of(LABEL_CREATED, IN_TRANSIT, IN_TRANSIT), AT),
                "DHL depot, Krakow", "Out for delivery with the courier");
        assertText(CarrierNetwork.scan(dhl, DELIVERY_FAILED, List.of(OUT_FOR_DELIVERY), AT), "Krakow",
                "Delivery attempt failed: the recipient was not available. The courier will try again");
        assertText(CarrierNetwork.scan(dhl, OUT_FOR_DELIVERY, List.of(OUT_FOR_DELIVERY, DELIVERY_FAILED), AT),
                "DHL depot, Krakow", "Out for delivery again (second attempt)");
        assertText(CarrierNetwork.scan(dhl, DELIVERED, List.of(OUT_FOR_DELIVERY), AT), "Krakow",
                "Delivered to the recipient");
        assertText(CarrierNetwork.confirmedDelivery(dhl, AT), "Krakow", "Delivery confirmed by the warehouse");
        assertThat(CarrierNetwork.confirmedDelivery(dhl, AT).getStatus()).isEqualTo(DELIVERED);
    }

    @ParameterizedTest
    @CsvSource({
            "INPOST, 123456789012345678901234, InPost Sorting Centre, Pruszkow, InPost depot, Gdansk, Delivered to the parcel locker, 1",
            "DPD, 12345678901234, DPD Hub, Strykow, DPD depot, Wroclaw, Delivered to the recipient, 2",
            "UPS, 1Z999AA10123456784, UPS Air Hub, Cologne, UPS depot, Wroclaw, Delivered to the recipient, 3",
            "DHL, 1234567895, DHL Hub, Leipzig, DHL depot, Warsaw, Delivered to the recipient, 2"
    })
    void everyCarrierHasItsOwnHubDepotAndTransitTime(Carrier carrier, String trackingNumber, String hubName,
                                                     String hubCity, String depotName, String depotCity,
                                                     String delivered, int transitDays) {
        ShipmentEntity shipment = shipment(carrier, trackingNumber);

        assertThat(CarrierNetwork.scan(shipment, IN_TRANSIT, List.of(LABEL_CREATED), AT).getLocation())
                .isEqualTo(hubName + ", " + hubCity);
        assertThat(CarrierNetwork.scan(shipment, IN_TRANSIT, List.of(IN_TRANSIT), AT).getLocation())
                .isEqualTo(depotName + ", " + depotCity);
        assertThat(CarrierNetwork.scan(shipment, DELIVERED, List.of(), AT).getDescription()).isEqualTo(delivered);
        assertThat(CarrierNetwork.transitDays(carrier)).isEqualTo(transitDays);
    }

    @Test
    void theCityIsStableForATrackingNumber() {
        assertThat(CarrierNetwork.city("1234567890")).isEqualTo("Krakow");
        assertThat(CarrierNetwork.city("1234567894")).isEqualTo("Lodz");
        assertThat(CarrierNetwork.city("1234567895")).isEqualTo("Warsaw");
    }

    private static void assertText(TrackingEventEntity event, String location, String description) {
        assertThat(event.getLocation()).isEqualTo(location);
        assertThat(event.getDescription()).isEqualTo(description);
    }

    private static ShipmentEntity shipment(Carrier carrier, String trackingNumber) {
        return ShipmentEntity.builder().id(7L).carrier(carrier).trackingNumber(trackingNumber).build();
    }
}
