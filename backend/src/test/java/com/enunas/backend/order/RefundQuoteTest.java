package com.enunas.backend.order;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundQuoteTest {

    private static BigDecimal e(String v) { return new BigDecimal(v); }

    /** 3 units, paid €300; commission net 45.00, vat 8.55, payout 246.45 (sums to 300.00). */
    private static RefundQuote.LineInput line(int already, int units) {
        return new RefundQuote.LineInput(1L, 3, e("300.00"), e("45.00"), e("8.55"), e("246.45"), already, units);
    }

    @Test
    void unitsBeforeTheLastAreRoundedUnitShares() {
        RefundQuote q = RefundQuote.of(List.of(line(0, 1)), BigDecimal.ZERO);
        assertThat(q.itemsAmount()).isEqualByComparingTo("100.00");
        assertThat(q.commissionNet()).isEqualByComparingTo("15.00");
        assertThat(q.commissionVat()).isEqualByComparingTo("2.85");
        assertThat(q.brandPayout()).isEqualByComparingTo("82.15");
    }

    @Test
    void lastUnitTakesTheRemainder() {
        RefundQuote.LineInput odd = new RefundQuote.LineInput(1L, 3, e("100.00"), e("15.00"), e("2.85"), e("82.15"), 0, 0);
        BigDecimal first = RefundQuote.of(List.of(withUnits(odd, 0, 1)), BigDecimal.ZERO).itemsAmount();
        BigDecimal second = RefundQuote.of(List.of(withUnits(odd, 1, 1)), BigDecimal.ZERO).itemsAmount();
        BigDecimal last = RefundQuote.of(List.of(withUnits(odd, 2, 1)), BigDecimal.ZERO).itemsAmount();
        assertThat(first).isEqualByComparingTo("33.33");
        assertThat(second).isEqualByComparingTo("33.33");
        assertThat(last).isEqualByComparingTo("33.34");
        assertThat(first.add(second).add(last)).isEqualByComparingTo("100.00");
    }

    @Test
    void sequentialPartialsSumExactlyToTheLineForEveryField() {
        RefundQuote a = RefundQuote.of(List.of(line(0, 2)), BigDecimal.ZERO);
        RefundQuote b = RefundQuote.of(List.of(line(2, 1)), BigDecimal.ZERO);
        assertThat(a.itemsAmount().add(b.itemsAmount())).isEqualByComparingTo("300.00");
        assertThat(a.commissionNet().add(b.commissionNet())).isEqualByComparingTo("45.00");
        assertThat(a.commissionVat().add(b.commissionVat())).isEqualByComparingTo("8.55");
        assertThat(a.brandPayout().add(b.brandPayout())).isEqualByComparingTo("246.45");
    }

    @Test
    void multipleLinesAndShippingAddUp() {
        RefundQuote.LineInput other = new RefundQuote.LineInput(2L, 1, e("27.00"), e("4.05"), e("0.77"), e("22.18"), 0, 1);
        RefundQuote q = RefundQuote.of(List.of(line(0, 3), other), e("4.99"));
        assertThat(q.itemsAmount()).isEqualByComparingTo("327.00");
        assertThat(q.shippingAmount()).isEqualByComparingTo("4.99");
        assertThat(q.total()).isEqualByComparingTo("331.99");
    }

    @Test
    void lineWhoseSnapshotDoesNotSumIsRefused() {
        RefundQuote.LineInput broken = new RefundQuote.LineInput(9L, 1, e("10.00"), e("1.00"), e("0.19"), e("5.00"), 0, 1);
        assertThatThrownBy(() -> RefundQuote.of(List.of(broken), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("9");
    }

    @Test
    void moreUnitsThanRemainingIsRefused() {
        assertThatThrownBy(() -> RefundQuote.of(List.of(line(2, 2)), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyItemWithoutPerItemPricingIsRefused() {
        OrderItem legacy = OrderItem.builder().quantity(1).customerGrossAfterDiscount(null).build();
        assertThatThrownBy(() -> RefundQuote.LineInput.of(legacy, 0, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("legacy");
    }

    private static RefundQuote.LineInput withUnits(RefundQuote.LineInput in, int already, int units) {
        return new RefundQuote.LineInput(in.orderItemId(), in.quantity(), in.customerGross(), in.commissionNet(),
                in.commissionVat(), in.brandPayout(), already, units);
    }
}
