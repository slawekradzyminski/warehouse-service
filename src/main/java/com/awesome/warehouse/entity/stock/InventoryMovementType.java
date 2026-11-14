package com.awesome.warehouse.entity.stock;

/** Movement types of the stock ledger; the same names as the backend's former inventory movements. */
public enum InventoryMovementType {
    INITIAL_STOCK,
    ADMIN_ADJUSTMENT,
    ORDER_DEDUCTED,
    ORDER_RESTORED,
    PURCHASE_RECEIVED,
    RETURN_RESTOCKED
}
