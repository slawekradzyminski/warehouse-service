package com.awesome.warehouse.entity.returns;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "return_items",
        indexes = {
            @Index(name = "idx_return_items_return", columnList = "return_id"),
            @Index(name = "idx_return_items_product", columnList = "product_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER on purpose: the native image cannot generate Hibernate proxies at runtime.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private ReturnRequestEntity returnRequest;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Product name as it appeared on the order. */
    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", length = 16)
    private ItemCondition condition;
}
