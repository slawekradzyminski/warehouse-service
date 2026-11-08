package com.awesome.warehouse.client;

import java.util.Map;

/** Body of {@code POST /internal/v1/notifications}. */
public record NotificationRequest(
        String username,
        NotificationTemplate template,
        String subject,
        String message,
        Map<String, String> properties) {

    public NotificationRequest {
        properties = Map.copyOf(properties);
    }
}
