package com.enunas.backend.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the net-based commission + VAT money snapshot (spec §7). The four headline
 * cases (lineGross = 119.00, rate = 18%, VAT 19%/19%) must reproduce to the cent. Asserts the HARD
 * invariant on every case and the SOFT (reporting) invariant on the clean domestic values.
 */
class CommissionVatSnapshotTest {

    private static final BigDecimal RATE  = new BigDecimal("0.18");
    private static final BigDecimal VAT_P = new BigDecimal("0.19");
    private static final BigDecimal VAT_S = new BigDecimal("0.19");

    private static OrderItem item() {
        return OrderItem.builder().lineGross(new BigDecimal("119.00")).quantity(1).build();
    }

    /** commissionNet + commissionVat + brandPayout == customerGrossAfterDiscount (exact, always). */
    private static void assertHardInvariant(OrderItem it) {
        assertThat(it.getCommissionNet().add(it.getCommissionVat()).add(it.getBrandPayout()))
                .isEqualByComparingTo(it.getCustomerGrossAfterDiscount());
    }

    @Test
    void domestic_noDiscount() {
        OrderItem it = item();
        it.applyMoneySnapshot(RATE, true, VAT_P, VAT_S);

        assertThat(it.getLineNet()).isEqualByComparingTo("100.00");
        assertThat(it.getLineVat()).isEqualByComparingTo("19.00");
        assertThat(it.getCommissionNet()).isEqualByComparingTo("18.00");
        assertThat(it.getCommissionVat()).isEqualByComparingTo("3.42");
        assertThat(it.getCommissionGross()).isEqualByComparingTo("21.42");
        assertThat(it.getCustomerGrossAfterDiscount()).isEqualByComparingTo("119.00");
        assertThat(it.getBrandPayout()).isEqualByComparingTo("97.58");
        assertThat(it.getBrandNetRevenue()).isEqualByComparingTo("82.00");
        assertThat(it.getReverseCharge()).isFalse();
        assertHardInvariant(it);
        // Soft invariant: brandPayout − productVatAfterDiscount + commissionVat == brandNetRevenue.
        assertThat(it.getBrandPayout().subtract(new BigDecimal("19.00")).add(it.getCommissionVat()))
                .isEqualByComparingTo(it.getBrandNetRevenue());
    }

    @Test
    void foreign_reverseCharge() {
        OrderItem it = item();
        it.applyMoneySnapshot(RATE, false, VAT_P, VAT_S);

        assertThat(it.getCommissionNet()).isEqualByComparingTo("18.00");
        assertThat(it.getCommissionVat()).isEqualByComparingTo("0.00");
        assertThat(it.getCommissionGross()).isEqualByComparingTo("18.00");
        assertThat(it.getCustomerGrossAfterDiscount()).isEqualByComparingTo("119.00");
        assertThat(it.getBrandPayout()).isEqualByComparingTo("101.00");
        assertThat(it.getBrandNetRevenue()).isEqualByComparingTo("82.00");
        assertThat(it.getReverseCharge()).isTrue();
        assertHardInvariant(it);
    }

    @Test
    void domestic_adminTenPercent() {
        OrderItem it = item();
        // ADMIN 10%: platform absorbs the whole net discount (itemDiscountNet = 100 × 0.10 = 10.00).
        it.applyMoneySnapshot(RATE, true, VAT_P, VAT_S,
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("0.10"));

        assertThat(it.getCommissionNet()).isEqualByComparingTo("8.00");
        assertThat(it.getCommissionVat()).isEqualByComparingTo("1.52");
        assertThat(it.getCommissionGross()).isEqualByComparingTo("9.52");
        assertThat(it.getCustomerGrossAfterDiscount()).isEqualByComparingTo("107.10");
        assertThat(it.getBrandPayout()).isEqualByComparingTo("97.58");
        assertThat(it.getBrandNetRevenue()).isEqualByComparingTo("82.00");
        assertHardInvariant(it);
        // productVatAfterDiscount = 107.10 − 90.00 = 17.10.
        assertThat(it.getBrandPayout().subtract(new BigDecimal("17.10")).add(it.getCommissionVat()))
                .isEqualByComparingTo(it.getBrandNetRevenue());
    }

    @Test
    void domestic_brandFifteenPercent() {
        OrderItem it = item();
        // BRAND 15%: net discount 15.00 split 7.50 / 7.50.
        it.applyMoneySnapshot(RATE, true, VAT_P, VAT_S,
                new BigDecimal("7.50"), new BigDecimal("7.50"), new BigDecimal("0.15"));

        assertThat(it.getCommissionNet()).isEqualByComparingTo("10.50");
        assertThat(it.getCommissionVat()).isEqualByComparingTo("2.00"); // 10.50×0.19 = 1.995 → 2.00
        assertThat(it.getCommissionGross()).isEqualByComparingTo("12.50");
        assertThat(it.getCustomerGrossAfterDiscount()).isEqualByComparingTo("101.15");
        assertThat(it.getBrandPayout()).isEqualByComparingTo("88.65");
        assertThat(it.getBrandNetRevenue()).isEqualByComparingTo("74.50");
        assertHardInvariant(it);
    }
}
