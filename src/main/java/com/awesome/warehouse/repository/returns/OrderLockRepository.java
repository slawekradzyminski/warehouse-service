package com.awesome.warehouse.repository.returns;

import com.awesome.warehouse.entity.returns.OrderLockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderLockRepository extends JpaRepository<OrderLockEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from OrderLockEntity l where l.orderId = :orderId")
    Optional<OrderLockEntity> findForUpdate(@Param("orderId") Long orderId);
}
