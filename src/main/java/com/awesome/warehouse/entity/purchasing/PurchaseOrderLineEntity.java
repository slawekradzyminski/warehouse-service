package com.awesome.warehouse.entity.purchasing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(
        name = "purchase_order_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_purchase_order_lines_product", columnNames = {"purchase_order_id", "product_id"}),
        indexes = @Index(name = "idx_purchase_order_lines_product", columnList = "product_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // EAGER on purpose: the native image cannot generate Hibernate proxies at runtime.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrderEntity purchaseOrder;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Product name when the line was written; the catalogue lives in the backend. */
    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private Integer orderedQuantity;

    @Column(nullable = false)
    @Builder.Default
    private Integer receivedQuantity = 0;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitCost;

    public int outstandingQuantity() {
        return orderedQuantity - receivedQuantity;
    }
}
