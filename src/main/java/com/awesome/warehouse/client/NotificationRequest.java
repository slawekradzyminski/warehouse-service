package com.awesome.warehouse.client;

import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/notifications}. The backend renders the email from the
 * template and its properties; a repeated requestId is accepted without a second email.
 */
public record NotificationRequest(
        UUID requestId,
        String username,
        NotificationTemplate template,
        Map<String, String> properties) {

    public NotificationRequest {
        properties = Map.copyOf(properties);
    }
}
