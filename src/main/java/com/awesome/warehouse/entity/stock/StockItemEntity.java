package com.awesome.warehouse.entity.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * The stock of one backend product. {@code ledgerVersion} grows by one with every ledger change.
 * A new item is always inserted (never merged), so two concurrent creates of one product fail
 * instead of overwriting each other.
 */
@Entity
@Table(name = "stock_items")
@Getter
@Setter
@NoArgsConstructor
public class StockItemEntity implements Persistable<Long> {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "ledger_version", nullable = false)
    private long ledgerVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    private boolean created;

    public StockItemEntity(Long productId, int quantity, long ledgerVersion, LocalDateTime now) {
        this.productId = productId;
        this.quantity = quantity;
        this.ledgerVersion = ledgerVersion;
        this.createdAt = now;
        this.updatedAt = now;
        this.created = true;
    }

    @Override
    public Long getId() {
        return productId;
    }

    @Override
    public boolean isNew() {
        return created;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        created = false;
    }
}
