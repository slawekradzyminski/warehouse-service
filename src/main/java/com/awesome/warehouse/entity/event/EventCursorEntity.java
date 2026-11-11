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
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * Position of one consumer in the backend event feed: the last sequence it handled.
 * A new instance is always inserted (never merged), so a concurrent first run cannot
 * reset a cursor another instance already advanced.
 */
@Entity
@Table(name = "event_cursors")
@Getter
@Setter
@NoArgsConstructor
public class EventCursorEntity implements Persistable<String> {

    @Id
    @Column(length = 64)
    private String consumer;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    private boolean created;

    public EventCursorEntity(String consumer, long lastSequence, LocalDateTime updatedAt) {
        this.consumer = consumer;
        this.lastSequence = lastSequence;
        this.updatedAt = updatedAt;
        this.created = true;
    }

    @Override
    public String getId() {
        return consumer;
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
