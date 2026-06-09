package com.enunas.backend.discount.integration;

import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 — a pre-V5 order_items row (all V5 snapshot columns NULL, including the two booleans) must
 * (1) LOAD without exception — the failure mode that primitive booleans would cause — and
 * (2) drive the ledger's legacy gross-basis fallback. Placed here to reuse the integration harness.
 */
class LegacyOrderFallbackTest extends AbstractDiscountIntegrationTest {

    @Autowired private PlatformTransactionManager txManager;

    @Test
    void legacyOrderRow_loadsWithNullSnapshot_andLedgerFallsBackToGross() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "100.00", 50);
        String custToken = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(custToken, null, List.of(item(listing, 1))));

        // Simulate a pre-V5 row: NULL every V5 snapshot column (incl. both booleans) and the legacy
        // gross fields, so the ledger must recompute from line_total × rate.
        jdbc.update("UPDATE order_items SET vat_rate_product = NULL, vat_rate_service = NULL, " +
                "line_net = NULL, line_vat = NULL, line_gross = NULL, base_commission_net = NULL, " +
                "commission_net = NULL, commission_vat = NULL, commission_gross = NULL, " +
                "customer_gross_after_discount = NULL, brand_payout = NULL, brand_net_revenue = NULL, " +
                "brand_is_domestic = NULL, reverse_charge = NULL, " +
                "platform_fee_amount = NULL, brand_payout_amount = NULL, commission_rate = NULL " +
                "WHERE order_id = ?", oid);

        TransactionTemplate tx = new TransactionTemplate(txManager);

        // (a) The entity loads and the NULL booleans materialize without exception.
        tx.executeWithoutResult(s -> {
            Order o = orderRepository.findById(oid).orElseThrow();
            OrderItem it = o.getItems().get(0);
            assertThat(it.getBrandIsDomestic()).isNull();
            assertThat(it.getReverseCharge()).isNull();
            assertThat(it.getCommissionNet()).isNull();
        });

        // (b) Ledger falls back to gross-basis: fee = 100 × 0.18 = 18.00, payout = 82.00.
        tx.executeWithoutResult(s ->
                ledgerService.recordOrderPayment(orderRepository.findById(oid).orElseThrow()));

        BigDecimal fee = jdbc.queryForObject(
                "SELECT platform_fee FROM ledger_entries WHERE order_id = ? AND entry_type = 'ORDER_PAYMENT'",
                BigDecimal.class, oid);
        BigDecimal payout = jdbc.queryForObject(
                "SELECT brand_payout FROM ledger_entries WHERE order_id = ? AND entry_type = 'ORDER_PAYMENT'",
                BigDecimal.class, oid);
        assertThat(fee).isEqualByComparingTo("18.00");
        assertThat(payout).isEqualByComparingTo("82.00");
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("82.00");
    }
}
