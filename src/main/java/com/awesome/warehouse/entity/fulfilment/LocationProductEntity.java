package com.awesome.warehouse.entity.fulfilment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A category stocked by a location. The category {@value #ANY_CATEGORY} matches every
 * category without a row of its own at that location.
 */
@Entity
@Table(name = "location_products",
        uniqueConstraints = @UniqueConstraint(name = "uk_location_products_category",
                columnNames = {"location_code", "category"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationProductEntity {

    public static final String ANY_CATEGORY = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location_code", nullable = false, length = 8)
    private String locationCode;

    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StockingRole role;
}
