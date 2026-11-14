package com.awesome.warehouse.entity.returns;

import com.awesome.warehouse.client.FulfilmentState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lock row for one order; see {@link com.awesome.warehouse.service.returns.OrderLocks}.
 * Fulfilment writes lock it too and keep the fulfilment state last mirrored to the
 * backend in {@code mirroredState} (null until the order is released).
 */
@Entity
@Table(name = "order_locks")
@Getter
@NoArgsConstructor
public class OrderLockEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FulfilmentState mirroredState;

    public OrderLockEntity(Long orderId) {
        this.orderId = orderId;
    }
}
