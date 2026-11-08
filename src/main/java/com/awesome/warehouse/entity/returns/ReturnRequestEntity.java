package com.awesome.warehouse.entity.returns;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Return request for a backend order. The order totals are captured when the
 * return is opened so the proportional refund never needs the backend again.
 */
@Entity
@Table(
        name = "return_requests",
        indexes = {
            @Index(name = "idx_return_requests_order", columnList = "order_id"),
            @Index(name = "idx_return_requests_user", columnList = "username,created_at"),
            @Index(name = "idx_return_requests_status", columnList = "status,created_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal orderTotalAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal orderSubtotalAmount;

    @Column(nullable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReturnStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReturnReason reason;

    @Column(name = "customer_comment", length = 1000)
    private String comment;

    @Column(length = 500)
    private String resolutionNote;

    @Column(precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<ReturnItemEntity> items = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime approvedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime refundedAt;

    public void addItem(ReturnItemEntity item) {
        items.add(item);
        item.setReturnRequest(this);
    }
}
