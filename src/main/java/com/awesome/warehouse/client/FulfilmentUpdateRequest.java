package com.awesome.warehouse.client;

/** Body of {@code PUT /internal/v1/orders/{id}/fulfilment}. */
public record FulfilmentUpdateRequest(FulfilmentState state) {
}
