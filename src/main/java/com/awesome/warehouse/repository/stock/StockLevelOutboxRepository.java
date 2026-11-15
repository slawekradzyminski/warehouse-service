package com.awesome.warehouse.repository.stock;

import com.awesome.warehouse.entity.stock.StockLevelOutboxEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StockLevelOutboxRepository extends JpaRepository<StockLevelOutboxEntity, Long> {

    /** Levels not pushed yet, oldest first. */
    List<StockLevelOutboxEntity> findAllByOrderByIdAsc(Pageable pageable);

    @Modifying
    @Query("delete from StockLevelOutboxEntity o where o.productId = :productId")
    int deleteByProduct(@Param("productId") Long productId);

    @Modifying
    @Query("""
            update StockLevelOutboxEntity o set o.attempts = o.attempts + 1, o.lastError = :error
            where o.id in :ids""")
    int recordFailure(@Param("ids") Collection<Long> ids, @Param("error") String error);
}
