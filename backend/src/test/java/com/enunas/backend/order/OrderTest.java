package com.enunas.backend.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Order#computeTotal()} — the single source of truth for
 * total = subtotal − discountAmount + shippingTotal. OrderService.createOrder() calls this exact
 * method, so a regression here (e.g. dropping the shippingTotal term) fails this test even while
 * shippingTotal is 0 in production, because these tests set it explicitly to a non-zero value.
 */
class OrderTest {

    @Test
    void computeTotal_appliesSubtotalMinusDiscountPlusShipping_withNonZeroShipping() {
        Order order = Order.builder()
                .subtotal(new BigDecimal("100.00"))
                .discountAmount(new BigDecimal("10.00"))
                .shippingTotal(new BigDecimal("4.99"))
                .build();

        // 100.00 - 10.00 + 4.99 = 94.99 — would be 90.00 if shippingTotal were dropped from the
        // formula, so this fails loudly on that regression.
        assertThat(order.computeTotal()).isEqualByComparingTo("94.99");
    }

    @Test
    void computeTotal_treatsNullDiscountAndShippingAsZero() {
        Order order = Order.builder()
                .subtotal(new BigDecimal("119.00"))
                .build(); // discountAmount and shippingTotal left null — today's no-discount path

        assertThat(order.computeTotal()).isEqualByComparingTo("119.00");
    }

    @Test
    void computeTotal_withDiscountAndZeroShipping_matchesCurrentFreeShippingBehavior() {
        Order order = Order.builder()
                .subtotal(new BigDecimal("119.00"))
                .discountAmount(new BigDecimal("11.90"))
                .shippingTotal(BigDecimal.ZERO)
                .build();

        assertThat(order.computeTotal()).isEqualByComparingTo("107.10");
    }
}
