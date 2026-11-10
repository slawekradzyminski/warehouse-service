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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One carrier scan of a shipment (append-only). */
@Entity
@Table(name = "tracking_events",
        indexes = @Index(name = "idx_tracking_events_shipment", columnList = "shipment_id,occurred_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackingEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TrackingStatus status;

    @Column(nullable = false, length = 120)
    private String location;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private LocalDateTime occurredAt;
}
