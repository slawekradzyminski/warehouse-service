package com.awesome.warehouse.repository.fulfilment;

import com.awesome.warehouse.entity.fulfilment.TrackingEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackingEventRepository extends JpaRepository<TrackingEventEntity, Long> {

    /** The shipment's timeline in recording order. */
    List<TrackingEventEntity> findByShipmentIdOrderByIdAsc(Long shipmentId);
}
