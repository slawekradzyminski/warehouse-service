package com.awesome.warehouse.repository.fulfilment;

import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, Long> {

    Optional<ShipmentEntity> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShipmentEntity s where s.id = :id")
    Optional<ShipmentEntity> findByIdForUpdate(@Param("id") Long id);

    Page<ShipmentEntity> findByStatus(ShipmentStatus status, Pageable pageable);

    boolean existsByTrackingNumber(String trackingNumber);

    /** Latest shipment with this tracking number (manual numbers are not unique). */
    Optional<ShipmentEntity> findFirstByTrackingNumberOrderByIdDesc(String trackingNumber);

    /** Simulated shipments in the given status whose latest carrier scan is not newer than {@code cutoff}. */
    @Query("""
            select s.id from ShipmentEntity s
            where s.simulated = true and s.status = :status
              and not exists (select e.id from TrackingEventEntity e
                              where e.shipmentId = s.id and e.occurredAt > :cutoff)
            order by s.id""")
    List<Long> findSimulatedIdsIdleSince(@Param("status") ShipmentStatus status,
                                         @Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
