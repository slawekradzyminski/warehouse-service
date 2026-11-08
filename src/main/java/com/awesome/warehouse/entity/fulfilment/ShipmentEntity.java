package com.awesome.warehouse.entity.fulfilment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Warehouse shipment for one backend order. The order lives in the backend,
 * so the shipment keeps the order id (no foreign key) plus the owner and unit
 * count captured when the order was released.
 */
@Entity
@Table(
        name = "shipments",
        uniqueConstraints = @UniqueConstraint(name = "uk_shipments_order", columnNames = "order_id"),
        indexes = @Index(name = "idx_shipments_status_created", columnList = "status,created_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Integer totalUnits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShipmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Carrier carrier;

    @Column(length = 32)
    private String trackingNumber;

    @Column(precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private String lastActor;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime pickedAt;
    private LocalDateTime packedAt;
    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime cancelledAt;
}
