package com.enunas.backend.ledger;

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
 * Reconciliation must stay clean once shipping money exists.
 *
 * <p>Regression guard for the bug where {@code ledgerNetOwed} summed ORDER_PAYMENT only while
 * {@code ecoNetOwed} (BrandEconomics.pendingBalance) already included SHIPPING_REVENUE — making
 * every brand with a shipping-inclusive paid order report permanent false drift, and making
 * {@code rebuildFromLedger} inject phantom debt equal to that brand's shipping revenue.
 */
@SuppressWarnings("rawtypes")
class ReconciliationServiceShippingTest extends AbstractDiscountIntegrationTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void paidOrderWithShipping_reconcilesClean() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));
        confirmPaid(oid);

        // Product payout 82.00 + shipping 4.99 credited to pendingBalance.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("86.99");

        ReconciliationService.DriftReport report = reconciliationService.checkBrand(a.brand().getId());
        assertThat(report.ledgerNetOwed()).isEqualByComparingTo("86.99");
        assertThat(report.ecoNetOwed()).isEqualByComparingTo("86.99");
        assertThat(report.drift()).isEqualByComparingTo("0.00");
        assertThat(report.clean()).as("drift report: %s", report).isTrue();
    }

    @Test
    void fullCancelAfterShippingRevenue_stillReconcilesClean() {
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
        assertThat(reconciliationService.checkBrand(a.brand().getId()).clean()).isTrue();

        ResponseEntity<Map> cancelled = rest.exchange(
                "/admin/orders/" + oid + "/status?status=CANCELLED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();

        // Shipping reversals are typed REFUND_REVERSAL, so the refund sum already covers them.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("0.00");

        ReconciliationService.DriftReport report = reconciliationService.checkBrand(a.brand().getId());
        assertThat(report.ledgerNetOwed()).isEqualByComparingTo("0.00");
        assertThat(report.ecoNetOwed()).isEqualByComparingTo("0.00");
        assertThat(report.clean()).as("drift report after cancel: %s", report).isTrue();
    }
}
