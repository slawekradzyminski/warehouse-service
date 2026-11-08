package com.awesome.warehouse.client;

/** Mirror of the shipment state stored on the backend order ({@code orders.fulfilment_state}). */
public enum FulfilmentState {
    NONE,
    AWAITING_PICK,
    PICKED,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
