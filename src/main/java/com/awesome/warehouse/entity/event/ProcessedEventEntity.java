package com.awesome.warehouse.entity.event;

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

/**
 * A feed event this service has already handled (or skipped); see {@code EventFeedPoller}.
 * Always inserted, so recording an event twice fails instead of silently merging.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor
public class ProcessedEventEntity implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Transient
    private boolean created;

    public ProcessedEventEntity(UUID eventId, LocalDateTime processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
        this.created = true;
    }

    @Override
    public UUID getId() {
        return eventId;
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
