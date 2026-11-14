package com.awesome.warehouse.repository.fulfilment;

import com.awesome.warehouse.entity.fulfilment.ShipmentLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ShipmentLineRepository extends JpaRepository<ShipmentLineEntity, Long> {

    List<ShipmentLineEntity> findByShipmentIdOrderByIdAsc(Long shipmentId);

    List<ShipmentLineEntity> findByShipmentIdInOrderByIdAsc(Collection<Long> shipmentIds);
}
