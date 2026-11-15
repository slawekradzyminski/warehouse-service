package com.awesome.warehouse.service.stock;

/** Lets tests outside this package switch the ledger's readiness. */
public final class StockTestAccess {

    private StockTestAccess() {
    }

    public static void markNotReady(StockReadiness readiness) {
        readiness.markNotReady();
    }

    public static void markReady(StockReadiness readiness) {
        readiness.markReady();
    }
}
