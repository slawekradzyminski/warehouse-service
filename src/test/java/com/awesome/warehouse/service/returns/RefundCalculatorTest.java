package com.awesome.warehouse.service.returns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RefundCalculatorTest {

    @ParameterizedTest(name = "{0} of goods, total {1} / subtotal {2} -> {3}")
    @CsvSource({
            "120.00, 126.00, 140.00, 108.00",
            "100.00, 100.00, 100.00, 100.00",
            "10.00, 2.00, 3.00, 6.67",
            "10.00, 1.00, 8.00, 1.25",
            "0.01, 1.00, 3.00, 0.00",
            "0.02, 1.00, 3.00, 0.01"
    })
    void refundIsScaledByThePaidShareAndRoundedHalfUp(String items, String total, String subtotal, String expected) {
        BigDecimal refund = RefundCalculator.proportionalRefund(
                new BigDecimal(items), new BigDecimal(total), new BigDecimal(subtotal));

        assertThat(refund).isEqualByComparingTo(expected);
        assertThat(refund.scale()).isEqualTo(2);
    }

    @Test
    void missingOrZeroSubtotalRefundsTheItemValue() {
        assertThat(RefundCalculator.proportionalRefund(new BigDecimal("10.005"), BigDecimal.ONE, null))
                .isEqualTo(new BigDecimal("10.01"));
        assertThat(RefundCalculator.proportionalRefund(new BigDecimal("10.004"), BigDecimal.ONE, BigDecimal.ZERO))
                .isEqualTo(new BigDecimal("10.00"));
        assertThat(RefundCalculator.proportionalRefund(BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("-1")))
                .isEqualTo(new BigDecimal("10.00"));
    }
}
