package com.awesome.warehouse.client;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One domain event from {@code GET /internal/v1/events}. {@code type} is the wire name
 * (for example {@code order.cancelled}); {@code data} always carries {@code orderId},
 * {@code status} and {@code totalAmount}.
 */
public record FeedEvent(
        long sequence,
        UUID id,
        String type,
        Instant occurredAt,
        Long orderId,
        String username,
        Map<String, Object> data) {

    public FeedEvent {
        // Values may be JSON null (for example failureCode), which Map.copyOf rejects.
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
