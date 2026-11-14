package com.awesome.warehouse.client;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One domain event from {@code GET /internal/v1/events}. {@code type} is the wire name
 * (for example {@code order.cancelled}); {@code data} always carries {@code orderId},
 * {@code status} and {@code totalAmount}. {@code requestId} and {@code traceId} (32 hex) are
 * the correlation ids of the backend request that wrote the event; they are optional (null for
 * events written by scheduled jobs or before backend 3.17.0). {@code spanId} (16 hex, backend
 * 3.21.0) is the span that wrote the event; consumers parent their span on it when tracing is on.
 */
public record FeedEvent(
        long sequence,
        UUID id,
        String type,
        Instant occurredAt,
        Long orderId,
        String username,
        Map<String, Object> data,
        String requestId,
        String traceId,
        String spanId) {

    public FeedEvent {
        // Values may be JSON null (for example failureCode), which Map.copyOf rejects.
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** An event without correlation ids. */
    public FeedEvent(long sequence, UUID id, String type, Instant occurredAt, Long orderId, String username,
                     Map<String, Object> data) {
        this(sequence, id, type, occurredAt, orderId, username, data, null, null, null);
    }

    /** An event with the correlation ids of backends before 3.21.0 (no span id). */
    public FeedEvent(long sequence, UUID id, String type, Instant occurredAt, Long orderId, String username,
                     Map<String, Object> data, String requestId, String traceId) {
        this(sequence, id, type, occurredAt, orderId, username, data, requestId, traceId, null);
    }
}
