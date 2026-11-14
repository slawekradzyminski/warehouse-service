package com.awesome.warehouse.entity.stock;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The stock deducted for one checkout. Always inserted, so a concurrent second reserve with the
 * same id fails instead of merging. Stores the correlation ids of the reserve request, so the
 * reaper's release continues that trace.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor
public class StockReservationEntity implements Persistable<UUID> {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationStatus status;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(length = 55)
    private String traceparent;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("productId")
    private List<StockReservationLineEntity> lines = new ArrayList<>();

    @Transient
    private boolean created;

    public StockReservationEntity(UUID reservationId, String username, LocalDateTime now) {
        this.reservationId = reservationId;
        this.username = username;
        this.status = ReservationStatus.PENDING_CONFIRMATION;
        this.createdAt = now;
        this.updatedAt = now;
        this.created = true;
    }

    public void addLine(StockReservationLineEntity line) {
        line.setReservation(this);
        lines.add(line);
    }

    @Override
    public UUID getId() {
        return reservationId;
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
