package com.awesome.warehouse.repository.stock;

import com.awesome.warehouse.entity.stock.StockItemEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockItemRepository extends JpaRepository<StockItemEntity, Long> {

    /** Locks the item row; callers lock several items in ascending product id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from StockItemEntity i where i.productId = :productId")
    Optional<StockItemEntity> findForUpdate(@Param("productId") Long productId);
}
