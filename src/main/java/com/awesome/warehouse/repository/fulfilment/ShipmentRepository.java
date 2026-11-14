package com.awesome.warehouse.repository.fulfilment;

import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, Long> {

    /** The order's parcels, oldest first (the parcel index order). */
    List<ShipmentEntity> findByOrderIdOrderByIdAsc(Long orderId);

    List<ShipmentEntity> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    boolean existsByOrderId(Long orderId);

    /** Parcels created before 1.5.0 whose lines still have to be read from the order snapshot. */
    List<ShipmentEntity> findByLinesPendingTrueOrderByIdAsc(Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShipmentEntity s set s.linesPending = false where s.id = :id")
    int markLinesFilled(@Param("id") Long id);

    /** Orders with at least one parcel in one of the given statuses, lowest order id first. */
    @Query("select distinct s.orderId from ShipmentEntity s where s.status in :statuses order by s.orderId")
    List<Long> findOrderIdsWithStatusIn(@Param("statuses") Collection<ShipmentStatus> statuses, Pageable pageable);

    /** The parcel's order id without loading the parcel (orderId never changes). */
    @Query("select s.orderId from ShipmentEntity s where s.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

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
