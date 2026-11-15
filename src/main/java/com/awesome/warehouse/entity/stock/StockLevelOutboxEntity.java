package com.awesome.warehouse.entity.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A stock level waiting to be pushed to the backend projection; deleted once the backend accepted it. */
@Entity
@Table(name = "stock_level_outbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockLevelOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "ledger_version", nullable = false)
    private long ledgerVersion;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** {@code X-Request-Id} of the request, event or job that changed the stock. */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    /** W3C {@code traceparent} of that hop; the push continues its trace. */
    @Column(length = 55)
    private String traceparent;
}
