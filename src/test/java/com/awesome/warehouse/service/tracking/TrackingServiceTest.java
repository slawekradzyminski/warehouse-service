package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.tracking.PublicTrackingDto;
import com.awesome.warehouse.dto.tracking.TrackingDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.fulfilment.TrackingEventEntity;
import com.awesome.warehouse.entity.fulfilment.TrackingStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.fulfilment.TrackingEventRepository;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import com.awesome.warehouse.service.notification.CustomerMessages.CustomerMessage;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 12, 0);
    private static final Duration STEP = Duration.ofMinutes(2);
    /** Delivered on the first attempt. */
    private static final String TRACKING = "1234567890";

    @Mock private ShipmentService shipmentService;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private TrackingEventRepository eventRepository;
    @Mock private NotificationOutbox notificationOutbox;
    @Mock private TrackingNumberGenerator trackingNumbers;

    private final List<TrackingEventEntity> events = new ArrayList<>();
    private TrackingService service;
    private ShipmentEntity shipment;

    @BeforeEach
    void setUp() {
        service = new TrackingService(shipmentService, shipmentRepository, eventRepository, notificationOutbox,
                trackingNumbers, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), STEP);
        shipment = ShipmentEntity.builder().id(7L).orderId(42L).username("buyer").status(ShipmentStatus.SHIPPED)
                .carrier(Carrier.DHL).trackingNumber(TRACKING).simulated(true).shippedAt(NOW.minusHours(1)).build();
        lenient().when(shipmentRepository.findById(7L)).thenReturn(Optional.of(shipment));
        lenient().when(shipmentService.lockForUpdate(7L)).thenReturn(shipment);
        lenient().when(eventRepository.findByShipmentIdOrderByIdAsc(7L)).thenAnswer(invocation -> List.copyOf(events));
        lenient().when(eventRepository.save(any())).thenAnswer(invocation -> {
            events.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    @Test
    void shippingWithoutATrackingNumberUsesAGeneratedOneAndMarksTheShipmentSimulated() {
        when(trackingNumbers.generate(Carrier.DHL)).thenReturn(TRACKING);
        ShipmentDto shipped = ShipmentDto.builder().id(7L).build();
        when(shipmentService.ship(7L, Carrier.DHL, TRACKING, true, "packer")).thenReturn(shipped);

        assertThat(service.ship(7L, Carrier.DHL, null, "packer")).isSameAs(shipped);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(TrackingStatus.LABEL_CREATED);
            assertThat(e.getOccurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void shippingWithATrackingNumberKeepsTheManualFlow() {
        when(shipmentService.ship(7L, Carrier.DHL, " 123 ", false, "packer"))
                .thenReturn(ShipmentDto.builder().id(7L).build());

        service.ship(7L, Carrier.DHL, " 123 ", "packer");

        verify(trackingNumbers, never()).generate(any());
        assertThat(events).extracting(TrackingEventEntity::getStatus).containsExactly(TrackingStatus.LABEL_CREATED);
    }

    @Test
    void aManualDeliveryRecordsAConfirmedDeliveryScan() {
        ShipmentDto delivered = ShipmentDto.builder().id(7L).build();
        when(shipmentService.deliver(7L, "packer")).thenReturn(delivered);

        assertThat(service.deliver(7L, "packer")).isSameAs(delivered);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(TrackingStatus.DELIVERED);
            assertThat(e.getDescription()).isEqualTo("Delivery confirmed by the warehouse");
            assertThat(e.getOccurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void theScheduledStepWaitsAFullStepIntervalAfterTheLatestScan() {
        scan(TrackingStatus.LABEL_CREATED, NOW);

        assertThat(service.advanceIfIdle(7L, NOW.plus(STEP).minusNanos(1000))).isFalse();
        assertThat(service.advanceIfIdle(7L, NOW.plus(STEP))).isTrue();
        assertThat(events).extracting(TrackingEventEntity::getStatus)
                .containsExactly(TrackingStatus.LABEL_CREATED, TrackingStatus.IN_TRANSIT);
        assertThat(events.getLast().getOccurredAt()).isEqualTo(NOW.plus(STEP));
        verify(notificationOutbox, never()).enqueue(any());
    }

    @Test
    void theScheduledStepSkipsShipmentsThatAreNotSimulatedOrNotShipped() {
        shipment.setSimulated(false);
        assertThat(service.advanceIfIdle(7L, NOW)).isFalse();
        shipment.setSimulated(true);
        shipment.setStatus(ShipmentStatus.DELIVERED);
        assertThat(service.advanceIfIdle(7L, NOW)).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void aSimulatedShipmentWithoutScansStartsWithItsLabel() {
        assertThat(service.advanceIfIdle(7L, NOW)).isTrue();
        assertThat(events).extracting(TrackingEventEntity::getStatus).containsExactly(TrackingStatus.LABEL_CREATED);
    }

    @Test
    void outForDeliveryAndAFailedAttemptNotifyTheCustomer() {
        shipment.setTrackingNumber("1234567894");
        scan(TrackingStatus.LABEL_CREATED, NOW);
        scan(TrackingStatus.IN_TRANSIT, NOW);
        scan(TrackingStatus.IN_TRANSIT, NOW);

        service.advance(7L, "operator");
        service.advance(7L, "operator");

        ArgumentCaptor<CustomerMessage> messages = ArgumentCaptor.forClass(CustomerMessage.class);
        verify(notificationOutbox, org.mockito.Mockito.times(2)).enqueue(messages.capture());
        assertThat(messages.getAllValues()).extracting(CustomerMessage::template).containsExactly(
                NotificationTemplate.SHIPMENT_OUT_FOR_DELIVERY, NotificationTemplate.SHIPMENT_DELIVERY_FAILED);
        assertThat(messages.getAllValues().getLast()).satisfies(m -> {
            assertThat(m.username()).isEqualTo("buyer");
            assertThat(m.properties()).isEqualTo(Map.of("orderId", "42", "carrier", "DHL",
                    "trackingNumber", "1234567894"));
        });
        verify(shipmentService, never()).deliver(anyLong(), any());
    }

    @Test
    void theDeliveredStepUsesTheRegularDelivery() {
        scan(TrackingStatus.LABEL_CREATED, NOW);
        scan(TrackingStatus.IN_TRANSIT, NOW);
        scan(TrackingStatus.IN_TRANSIT, NOW);
        scan(TrackingStatus.OUT_FOR_DELIVERY, NOW);

        assertThat(service.advanceIfIdle(7L, NOW.plusHours(1))).isTrue();

        verify(shipmentService).deliver(7L, TrackingService.CARRIER_ACTOR);
        verify(notificationOutbox, never()).enqueue(any());
        assertThat(events.getLast().getStatus()).isEqualTo(TrackingStatus.DELIVERED);
    }

    @Test
    void advancingAShipmentThatIsNotSimulatedAndShippedIsAConflict() {
        shipment.setStatus(ShipmentStatus.PACKED);

        assertThatThrownBy(() -> service.advance(7L, "operator"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Only simulated SHIPPED shipments can be advanced");
                });
        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setSimulated(false);
        assertThatThrownBy(() -> service.advance(7L, "operator")).isInstanceOf(CustomException.class);
        when(shipmentService.lockForUpdate(8L))
                .thenThrow(new CustomException("Shipment not found", HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.advance(8L, "operator")).hasMessage("Shipment not found");
    }

    @Test
    void idleShipmentsAreThoseWithoutAScanInTheLastStepInterval() {
        when(shipmentRepository.findSimulatedIdsIdleSince(ShipmentStatus.SHIPPED, NOW.minus(STEP),
                PageRequest.of(0, TrackingService.BATCH_SIZE))).thenReturn(List.of(7L));

        assertThat(service.idleSimulatedShipments(NOW)).containsExactly(7L);
    }

    @Test
    void theEstimateFollowsTheRemainingSimulatedSteps() {
        scan(TrackingStatus.LABEL_CREATED, NOW.minusMinutes(1));
        scan(TrackingStatus.IN_TRANSIT, NOW);

        TrackingDto tracking = service.advance(7L, "operator");

        assertThat(tracking.getCurrentStatus()).isEqualTo(TrackingStatus.IN_TRANSIT);
        assertThat(tracking.getEstimatedDelivery()).isEqualTo(NOW.plus(STEP.multipliedBy(2)));
        assertThat(tracking.getShipmentId()).isEqualTo(7L);
        assertThat(tracking.getOrderId()).isEqualTo(42L);
        assertThat(tracking.getCarrier()).isEqualTo(Carrier.DHL);
        assertThat(tracking.getTrackingNumber()).isEqualTo(TRACKING);
        assertThat(tracking.isSimulated()).isTrue();
        assertThat(tracking.getEvents()).hasSize(3);
        assertThat(tracking.getEvents().getFirst()).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(TrackingStatus.LABEL_CREATED);
            assertThat(e.getOccurredAt()).isEqualTo(NOW.minusMinutes(1));
            assertThat(e.getLocation()).isEqualTo("Awesome Testing Warehouse, Krakow");
            assertThat(e.getDescription()).startsWith("Shipping label created");
        });
    }

    @Test
    void theEstimateOfAManualParcelIsTheCarrierTransitTimeAndNoneBeforeShipping() {
        when(shipmentService.trackOrder(42L, "buyer", false)).thenReturn(ShipmentDto.builder().id(7L).build());
        shipment.setSimulated(false);
        shipment.setCarrier(Carrier.UPS);

        assertThat(service.trackOrder(42L, "buyer", false).getEstimatedDelivery())
                .isEqualTo(NOW.minusHours(1).plusDays(3));
        shipment.setSimulated(true);
        assertThat(service.trackOrder(42L, "buyer", false).getEstimatedDelivery())
                .as("simulated without scans").isEqualTo(NOW.minusHours(1).plusDays(3));
        shipment.setStatus(ShipmentStatus.PACKED);
        assertThat(service.trackOrder(42L, "buyer", false).getEstimatedDelivery()).isNull();
        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(NOW);
        assertThat(service.trackOrder(42L, "buyer", false).getEstimatedDelivery()).isEqualTo(NOW);
    }

    @Test
    void aShipmentRemovedConcurrentlyIsNotFound() {
        when(shipmentService.trackOrder(42L, "buyer", false)).thenReturn(ShipmentDto.builder().id(8L).build());

        assertThatThrownBy(() -> service.trackOrder(42L, "buyer", false))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Shipment not found");
                });
    }

    @Test
    void publicTrackingNormalisesTheNumberAndHidesTheOrder() {
        when(shipmentRepository.findFirstByTrackingNumberOrderByIdDesc("1Z999AA10123456784"))
                .thenReturn(Optional.of(shipment));
        scan(TrackingStatus.LABEL_CREATED, NOW);

        PublicTrackingDto tracking = service.trackPublic(" 1z999aa10123456784 ");

        assertThat(tracking.getShipmentId()).isEqualTo(7L);
        assertThat(tracking.getTrackingNumber()).isEqualTo(TRACKING);
        assertThat(tracking.getCurrentStatus()).isEqualTo(TrackingStatus.LABEL_CREATED);
        assertThat(tracking.getEvents()).hasSize(1);
        assertThatThrownBy(() -> service.trackPublic("unknown"))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Tracking number not found");
                });
    }

    private void scan(TrackingStatus status, LocalDateTime at) {
        events.add(TrackingEventEntity.builder().shipmentId(7L).status(status).location(
                status == TrackingStatus.LABEL_CREATED ? "Awesome Testing Warehouse, Krakow" : "somewhere")
                .description(status == TrackingStatus.LABEL_CREATED ? "Shipping label created" : "scan")
                .occurredAt(at).build());
    }
}
