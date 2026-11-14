package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.tracking.PublicTrackingDto;
import com.awesome.warehouse.dto.tracking.TrackingDto;
import com.awesome.warehouse.dto.tracking.TrackingEventDto;
import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.fulfilment.TrackingEventEntity;
import com.awesome.warehouse.entity.fulfilment.TrackingStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.fulfilment.TrackingEventRepository;
import com.awesome.warehouse.service.fulfilment.ShipmentCancelledException;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import com.awesome.warehouse.service.notification.CustomerMessages;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Carrier tracking. Hand-over and manual delivery go through {@link ShipmentService}
 * (backend mirror, customer notifications) and additionally record tracking scans.
 * A shipment handed over without a tracking number is simulated: it gets a generated
 * number and {@link CarrierSimulator} advances it one scan per step interval; its
 * DELIVERED step is the regular delivery.
 */
@Service
public class TrackingService {

    /** lastActor of a shipment delivered by the simulated carrier. */
    static final String CARRIER_ACTOR = "carrier";
    static final int BATCH_SIZE = 100;
    static final String NOT_ADVANCEABLE = "Only simulated SHIPPED shipments can be advanced";

    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;
    private final TrackingEventRepository eventRepository;
    private final NotificationOutbox notificationOutbox;
    private final TrackingNumberGenerator trackingNumbers;
    private final Clock warehouseClock;
    private final Duration stepInterval;

    public TrackingService(ShipmentService shipmentService, ShipmentRepository shipmentRepository,
                           TrackingEventRepository eventRepository, NotificationOutbox notificationOutbox,
                           TrackingNumberGenerator trackingNumbers, Clock warehouseClock,
                           @Value("${warehouse.carrier.step-interval:PT2M}") Duration stepInterval) {
        this.shipmentService = shipmentService;
        this.shipmentRepository = shipmentRepository;
        this.eventRepository = eventRepository;
        this.notificationOutbox = notificationOutbox;
        this.trackingNumbers = trackingNumbers;
        this.warehouseClock = warehouseClock;
        this.stepInterval = stepInterval;
    }

    /**
     * PACKED to SHIPPED plus the LABEL_CREATED scan. Without a tracking number the
     * parcel goes to the simulated carrier, which generates one.
     */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto ship(Long id, Carrier carrier, String trackingNumber, String actor) {
        boolean simulated = trackingNumber == null;
        ShipmentDto shipped = shipmentService.ship(id, carrier,
                simulated ? trackingNumbers.generate(carrier) : trackingNumber, simulated, actor);
        ShipmentEntity shipment = find(id);
        eventRepository.save(CarrierNetwork.scan(shipment, TrackingStatus.LABEL_CREATED, List.of(), now()));
        return shipped;
    }

    /** Manual delivery confirmation (SHIPPED to DELIVERED) plus the DELIVERED scan. */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public ShipmentDto deliver(Long id, String actor) {
        ShipmentDto delivered = shipmentService.deliver(id, actor);
        eventRepository.save(CarrierNetwork.confirmedDelivery(find(id), now()));
        return delivered;
    }

    /** Fast-forwards a simulated SHIPPED shipment by one scan, regardless of the step interval. */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public TrackingDto advance(Long id, String actor) {
        ShipmentEntity shipment = lock(id);
        if (!advanceable(shipment)) {
            throw new CustomException(NOT_ADVANCEABLE, HttpStatus.CONFLICT);
        }
        step(shipment, history(id), now(), actor);
        return tracking(shipment);
    }

    /** Simulated SHIPPED shipments without a scan in the last step interval, oldest first. */
    @Transactional(readOnly = true)
    public List<Long> idleSimulatedShipments(LocalDateTime now) {
        return shipmentRepository.findSimulatedIdsIdleSince(ShipmentStatus.SHIPPED, now.minus(stepInterval),
                PageRequest.of(0, BATCH_SIZE));
    }

    /**
     * Scheduled step: advances the shipment by one scan if it is still a simulated
     * SHIPPED shipment and its latest scan is at least one step interval old. The row
     * lock and the age check make a repeated or concurrent run a no-op.
     *
     * @return whether the shipment advanced
     */
    @Transactional(noRollbackFor = ShipmentCancelledException.class)
    public boolean advanceIfIdle(Long id, LocalDateTime now) {
        ShipmentEntity shipment = lock(id);
        List<TrackingEventEntity> history = history(id);
        if (!advanceable(shipment)
                || (!history.isEmpty() && history.getLast().getOccurredAt().isAfter(now.minus(stepInterval)))) {
            return false;
        }
        step(shipment, history, now, CARRIER_ACTOR);
        return true;
    }

    /** Not transactional: the shipment lookup may ask the backend, which must not hold a connection. */
    public TrackingDto trackOrder(Long orderId, String username, boolean admin) {
        ShipmentDto shipment = shipmentService.trackOrder(orderId, username, admin);
        return tracking(find(shipment.getId()));
    }

    @Transactional(readOnly = true)
    public PublicTrackingDto trackPublic(String rawTrackingNumber) {
        String trackingNumber = rawTrackingNumber.trim().toUpperCase(Locale.ROOT);
        return shipmentRepository.findFirstByTrackingNumberOrderByIdDesc(trackingNumber)
                .map(shipment -> PublicTrackingDto.from(tracking(shipment)))
                .orElseThrow(() -> new CustomException("Tracking number not found", HttpStatus.NOT_FOUND));
    }

    private void step(ShipmentEntity shipment, List<TrackingEventEntity> history, LocalDateTime at, String actor) {
        List<TrackingStatus> statuses = statuses(history);
        TrackingStatus next = CarrierRoute.next(statuses,
                CarrierRoute.failsFirstAttempt(shipment.getTrackingNumber()));
        if (next == TrackingStatus.DELIVERED) {
            shipmentService.deliver(shipment.getId(), actor);
        } else if (next == TrackingStatus.OUT_FOR_DELIVERY) {
            notificationOutbox.enqueue(CustomerMessages.outForDelivery(shipment));
        } else if (next == TrackingStatus.DELIVERY_FAILED) {
            notificationOutbox.enqueue(CustomerMessages.deliveryFailed(shipment));
        }
        eventRepository.save(CarrierNetwork.scan(shipment, next, statuses, at));
    }

    private static boolean advanceable(ShipmentEntity shipment) {
        return shipment.isSimulated() && shipment.getStatus() == ShipmentStatus.SHIPPED;
    }

    private TrackingDto tracking(ShipmentEntity shipment) {
        List<TrackingEventEntity> history = history(shipment.getId());
        return TrackingDto.builder()
                .shipmentId(shipment.getId())
                .orderId(shipment.getOrderId())
                .carrier(shipment.getCarrier())
                .trackingNumber(shipment.getTrackingNumber())
                .simulated(shipment.isSimulated())
                .currentStatus(history.isEmpty() ? null : history.getLast().getStatus())
                .estimatedDelivery(estimatedDelivery(shipment, history))
                .events(history.stream().map(TrackingService::toDto).toList())
                .build();
    }

    /**
     * Delivered: the delivery time. Simulated and in transit: the latest scan plus one
     * step interval per remaining scan. Manual and in transit: the hand-over plus the
     * carrier's nominal transit days. Otherwise (not shipped yet, cancelled) none.
     */
    private LocalDateTime estimatedDelivery(ShipmentEntity shipment, List<TrackingEventEntity> history) {
        if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
            return shipment.getDeliveredAt();
        }
        if (shipment.getStatus() != ShipmentStatus.SHIPPED) {
            return null;
        }
        if (shipment.isSimulated() && !history.isEmpty()) {
            int remaining = CarrierRoute.remainingSteps(statuses(history),
                    CarrierRoute.failsFirstAttempt(shipment.getTrackingNumber()));
            return history.getLast().getOccurredAt().plus(stepInterval.multipliedBy(remaining));
        }
        return shipment.getShippedAt().plusDays(CarrierNetwork.transitDays(shipment.getCarrier()));
    }

    private static TrackingEventDto toDto(TrackingEventEntity event) {
        return TrackingEventDto.builder()
                .status(event.getStatus())
                .location(event.getLocation())
                .description(event.getDescription())
                .occurredAt(event.getOccurredAt())
                .build();
    }

    private static List<TrackingStatus> statuses(List<TrackingEventEntity> history) {
        return history.stream().map(TrackingEventEntity::getStatus).toList();
    }

    private List<TrackingEventEntity> history(Long shipmentId) {
        return eventRepository.findByShipmentIdOrderByIdAsc(shipmentId);
    }

    /** The order lock first, then the parcel row: the order every fulfilment write takes them in. */
    private ShipmentEntity lock(Long id) {
        return shipmentService.lockForUpdate(id);
    }

    private ShipmentEntity find(Long id) {
        return shipmentRepository.findById(id)
                .orElseThrow(() -> new CustomException("Shipment not found", HttpStatus.NOT_FOUND));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(warehouseClock);
    }
}
