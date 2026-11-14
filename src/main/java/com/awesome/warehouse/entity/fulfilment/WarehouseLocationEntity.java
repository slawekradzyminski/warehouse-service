package com.awesome.warehouse.entity.fulfilment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A warehouse location that ships parcels. Lower {@code priority} numbers are preferred;
 * inactive locations receive no new allocations (parcels already there stay).
 */
@Entity
@Table(name = "warehouse_locations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseLocationEntity {

    @Id
    @Column(length = 8)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int priority;
}
