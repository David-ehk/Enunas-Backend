package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping-domain ledger effects: SHIPPING_REVENUE booked on payment confirmation, and a full
 * pre-shipment cancel reversing both product AND shipping money together.
 */
@SuppressWarnings("rawtypes")
class ShippingLedgerIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void paymentConfirmation_recordsShippingRevenue_andCreditsBrand() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));
        confirmPaid(oid);

        BigDecimal shippingRevenue = jdbc.queryForObject(
                "SELECT brand_payout FROM ledger_entries WHERE order_id = ? AND entry_type = 'SHIPPING_REVENUE'",
                BigDecimal.class, oid);
        assertThat(shippingRevenue).isEqualByComparingTo("4.99");

        // Product 82.00 (100 gross, 18% commission on net-of-19%-VAT) + shipping 4.99 = 86.99 pending.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("86.99");
    }

    @Test
    void fullPreShipmentCancel_reversesBothProductAndShippingLedgerEntries() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String custToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");

        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));
        confirmPaid(oid);
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("86.99");

        ResponseEntity<Map> cancelled = rest.exchange(
                "/admin/orders/" + oid + "/status?status=CANCELLED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();

        // Both the 82.00 product payout and the 4.99 shipping payout reversed -> back to zero.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("0.00");
        BigDecimal shippingReversal = jdbc.queryForObject(
                "SELECT brand_payout FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL' " +
                "AND external_reference_id LIKE '%:SHIPPING'", BigDecimal.class, oid);
        assertThat(shippingReversal).isEqualByComparingTo("-4.99");
    }
}
