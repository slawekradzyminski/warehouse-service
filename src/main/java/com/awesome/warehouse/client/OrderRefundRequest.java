package com.awesome.warehouse.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Body of {@code POST /internal/v1/orders/{id}/refunds}; idempotent per requestId. */
public record OrderRefundRequest(UUID requestId, BigDecimal amount, String reference) {
}
