package com.enunas.backend.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the per-item money snapshot — the single source of truth the ledger
 * reads. Verifies the worked examples for both discount types (G = €100, rate = 18%).
 */
class OrderItemDiscountSnapshotTest {

    private static OrderItem itemWithLineTotal(String lineTotal) {
        return OrderItem.builder()
                .lineTotal(new BigDecimal(lineTotal))
                .quantity(1)
                .build();
    }

    @Test
    void noDiscount_keepsStandardCommissionSplit() {
        OrderItem item = itemWithLineTotal("100.00");

        item.applyCommissionSnapshot(new BigDecimal("0.18"));

        assertThat(item.getPlatformFeeAmount()).isEqualByComparingTo("18.00");
        assertThat(item.getBrandPayoutAmount()).isEqualByComparingTo("82.00");
        assertThat(item.getItemDiscountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void adminDiscount_isFullyAbsorbedByPlatform_brandPayoutUnchanged() {
        OrderItem item = itemWithLineTotal("100.00");

        // ADMIN 10%: platform absorbs the whole €10, brand absorbs nothing.
        item.applyCommissionSnapshot(new BigDecimal("0.18"),
                new BigDecimal("10.00"), BigDecimal.ZERO);

        assertThat(item.getBrandPayoutAmount()).isEqualByComparingTo("82.00"); // unchanged
        assertThat(item.getPlatformFeeAmount()).isEqualByComparingTo("8.00");  // 18 - 10
        assertThat(item.getItemDiscountAmount()).isEqualByComparingTo("10.00");
        // Customer pays = payout + fee = €90.
        assertThat(item.getBrandPayoutAmount().add(item.getPlatformFeeAmount()))
                .isEqualByComparingTo("90.00");
    }

    @Test
    void brandDiscount_isSplitFiftyFifty() {
        OrderItem item = itemWithLineTotal("100.00");

        // BRAND 15%: €15 discount split 7.50 / 7.50.
        item.applyCommissionSnapshot(new BigDecimal("0.18"),
                new BigDecimal("7.50"), new BigDecimal("7.50"));

        assertThat(item.getBrandPayoutAmount()).isEqualByComparingTo("74.50"); // 82 - 7.50
        assertThat(item.getPlatformFeeAmount()).isEqualByComparingTo("10.50"); // 18 - 7.50
        assertThat(item.getItemDiscountAmount()).isEqualByComparingTo("15.00");
        // Customer pays = €85.
        assertThat(item.getBrandPayoutAmount().add(item.getPlatformFeeAmount()))
                .isEqualByComparingTo("85.00");
    }
}
