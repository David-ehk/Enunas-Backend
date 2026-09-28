package com.enunas.backend.order.integration;

import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerItemCancellationIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @Autowired TransactionTemplate transactionTemplate;

    private void inTx(long orderId, Consumer<Order> action) {
        transactionTemplate.executeWithoutResult(s -> action.accept(orderRepository.findById(orderId).orElseThrow()));
    }

    private static OrderItem itemOf(Order order, long itemId) {
        return order.getItems().stream().filter(i -> i.getId() == itemId).findFirst().orElseThrow();
    }

    @Test
    void reversesExactlyTheCancelledItems_andNothingElse() {
        Fixture f = paidTwoBrandOrder(null);
        BigDecimal pendingA = brandPending(f.brandAId());
        BigDecimal pendingB = brandPending(f.brandBId());

        inTx(f.orderId(), o -> ledgerService.recordItemCancellationReversal(o, List.of(itemOf(o, f.a1())), "re_1"));

        List<Map<String, Object>> rev = reversals(f.orderId());
        assertThat(rev).hasSize(1);
        Map<String, Object> r = rev.get(0);
        assertThat(r.get("external_reference_id")).isEqualTo("re_1");
        assertThat(((Number) r.get("brand_partner_id")).longValue()).isEqualTo(f.brandAId());
        assertThat((BigDecimal) r.get("brand_payout"))
                .isEqualByComparingTo(itemMoney(f.a1(), "brand_payout_amount").negate());
        assertThat((BigDecimal) r.get("platform_fee"))
                .isEqualByComparingTo(itemMoney(f.a1(), "commission_net").negate());
        assertThat((BigDecimal) r.get("commission_vat"))
                .isEqualByComparingTo(itemMoney(f.a1(), "commission_vat").negate());
        assertThat(brandPending(f.brandAId()))
                .isEqualByComparingTo(pendingA.subtract(itemMoney(f.a1(), "brand_payout_amount")));
        assertThat(brandPending(f.brandBId())).isEqualByComparingTo(pendingB);
    }

    @Test
    void itemReversal_isIdempotentPerRefundId() {
        Fixture f = paidTwoBrandOrder(null);
        inTx(f.orderId(), o -> ledgerService.recordItemCancellationReversal(o, List.of(itemOf(o, f.a1())), "re_1"));
        inTx(f.orderId(), o -> ledgerService.recordItemCancellationReversal(o, List.of(itemOf(o, f.a1())), "re_1"));
        assertThat(reversals(f.orderId())).hasSize(1);
    }

    @Test
    void emptiedBrandShipping_isReversedOnceInFull() {
        Fixture f = paidTwoBrandOrder(null);
        inTx(f.orderId(), o -> ledgerService.reverseShippingForEmptiedBrand(o, f.brandBId(), "re_s"));
        inTx(f.orderId(), o -> ledgerService.reverseShippingForEmptiedBrand(o, f.brandBId(), "re_s"));

        List<Map<String, Object>> rev = reversals(f.orderId());
        assertThat(rev).hasSize(1);
        assertThat(rev.get(0).get("external_reference_id")).isEqualTo("re_s:SHIPPING");
        assertThat((BigDecimal) rev.get(0).get("brand_payout"))
                .isEqualByComparingTo(shippingOf(f.orderId(), f.brandBId()).negate());
    }

    /** D22: a later pro-rata return must skip items an item-cancel already reversed exactly. */
    @Test
    void laterReturnReversal_excludesSettledCancelledItems_fromBasisAndSum() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.a1(), f.a3());
        // Make a1 deliberately disproportionate: with a1 still in the pro-rata basis and sum, a2's
        // reversal could not come out at exactly a2's own values.
        jdbc.update("UPDATE order_items SET brand_payout_amount = 0, commission_net = 0, commission_vat = 0 "
                + "WHERE id = ?", f.a1());
        BigDecimal a2Gross = itemMoney(f.a2(), "line_gross");

        inTx(f.orderId(), o -> ledgerService.recordRefund(o, f.brandAId(), a2Gross, "ret_a2"));

        Map<String, Object> r = reversals(f.orderId()).get(0);
        assertThat((BigDecimal) r.get("brand_payout"))
                .isEqualByComparingTo(itemMoney(f.a2(), "brand_payout_amount").negate());
        assertThat((BigDecimal) r.get("platform_fee"))
                .isEqualByComparingTo(itemMoney(f.a2(), "commission_net").negate());
    }
}
