package com.awesome.warehouse.entity.stock;

/** Life cycle of a checkout reservation. */
public enum ReservationStatus {
    /** Stock deducted; the backend has not confirmed the order yet. */
    PENDING_CONFIRMATION,
    /** The backend saved the order; the stock stays deducted. */
    CONFIRMED,
    /** Compensated or expired; the stock was put back. */
    RELEASED
}
