package com.awesome.warehouse.entity.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** One change of a product's stock. Only {@code orderId} changes after insert (on confirm). */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private InventoryMovementType type;

    @Column(nullable = false, updatable = false)
    private int delta;

    @Column(name = "quantity_after", nullable = false, updatable = false)
    private int quantityAfter;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    /** The item's ledger version after this movement; null for movements imported from the backend. */
    @Column(name = "ledger_version", updatable = false)
    private Long ledgerVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
