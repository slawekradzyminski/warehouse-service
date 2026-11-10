package com.awesome.warehouse.entity.fulfilment;

/** Carrier tracking status of a shipped parcel, as shown to customers. */
public enum TrackingStatus {
    LABEL_CREATED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERY_FAILED,
    DELIVERED
}
