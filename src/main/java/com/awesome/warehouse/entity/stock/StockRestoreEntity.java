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
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

/** The stock restore of one cancelled order; always inserted, so a second restore of the order fails. */
@Entity
@Table(name = "stock_restores")
@Getter
@NoArgsConstructor
public class StockRestoreEntity implements Persistable<Long> {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "restored_at", nullable = false)
    private LocalDateTime restoredAt;

    @Transient
    private boolean created;

    public StockRestoreEntity(Long orderId, UUID eventId, LocalDateTime restoredAt) {
        this.orderId = orderId;
        this.eventId = eventId;
        this.restoredAt = restoredAt;
        this.created = true;
    }

    @Override
    public Long getId() {
        return orderId;
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
