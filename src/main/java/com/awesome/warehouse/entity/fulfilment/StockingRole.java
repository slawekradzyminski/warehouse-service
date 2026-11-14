package com.awesome.warehouse.entity.fulfilment;

/** How a location stocks a category: PRIMARY ships it normally, BACKUP when no primary location can. */
public enum StockingRole {
    PRIMARY,
    BACKUP
}
