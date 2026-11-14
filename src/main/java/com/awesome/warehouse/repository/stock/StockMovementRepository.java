package com.awesome.warehouse.repository.stock;

import com.awesome.warehouse.entity.stock.StockMovementEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovementEntity, Long> {

    Optional<StockMovementEntity> findByProductIdAndRequestId(Long productId, UUID requestId);

    Page<StockMovementEntity> findByProductIdOrderByCreatedAtDescIdDesc(Long productId, Pageable pageable);

    List<StockMovementEntity> findByReservationIdOrderByIdAsc(UUID reservationId);

    /** The largest movement id, or 0 without movements. */
    @Query("select coalesce(max(m.id), 0) from StockMovementEntity m")
    long maxId();

    @Modifying
    @Query("delete from StockMovementEntity m where m.productId = :productId")
    int deleteByProduct(@Param("productId") Long productId);
}
