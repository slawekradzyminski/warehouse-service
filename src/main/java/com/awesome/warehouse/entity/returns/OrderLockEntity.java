package com.awesome.warehouse.entity.returns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Lock row for one order; see {@link com.awesome.warehouse.service.returns.OrderLocks}. */
@Entity
@Table(name = "order_locks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrderLockEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;
}
