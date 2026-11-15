package com.awesome.warehouse.repository.stock;

import com.awesome.warehouse.entity.stock.ReservationStatus;
import com.awesome.warehouse.entity.stock.StockReservationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservationEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.reservationId = :reservationId")
    Optional<StockReservationEntity> findForUpdate(@Param("reservationId") UUID reservationId);

    /** Reservations in the given status created before {@code cutoff}, oldest first. */
    List<StockReservationEntity> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            ReservationStatus status, LocalDateTime cutoff, Pageable pageable);

    /** Removes a deleted product's lines from every reservation. */
    @Modifying
    @Query("delete from StockReservationLineEntity l where l.productId = :productId")
    int deleteLinesOfProduct(@Param("productId") Long productId);

    /** Removes reservations left without lines (after {@link #deleteLinesOfProduct}). */
    @Modifying
    @Query("delete from StockReservationEntity r where not exists "
            + "(select l.id from StockReservationLineEntity l where l.reservation = r)")
    int deleteWithoutLines();
}
