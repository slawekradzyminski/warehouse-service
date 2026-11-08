package com.awesome.warehouse.service.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Refund arithmetic, ported from the backend's {@code CouponPricing.proportionalRefund}. */
public final class RefundCalculator {

    private RefundCalculator() {
    }

    /**
     * Refund for returned goods, scaled by the share of the order that was
     * actually paid so coupon discounts are returned proportionally.
     */
    public static BigDecimal proportionalRefund(BigDecimal itemsValue, BigDecimal orderTotal, BigDecimal orderSubtotal) {
        if (orderSubtotal == null || orderSubtotal.signum() <= 0) {
            return itemsValue.setScale(2, RoundingMode.HALF_UP);
        }
        return itemsValue.multiply(orderTotal).divide(orderSubtotal, 2, RoundingMode.HALF_UP);
    }
}
