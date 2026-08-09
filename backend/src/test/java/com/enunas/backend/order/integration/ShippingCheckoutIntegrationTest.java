package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping-domain checkout flow: per-brand snapshot creation, correct order total, global-default
 * fallback, and legacy (pre-feature) orders staying readable with no snapshot rows.
 */
@SuppressWarnings("rawtypes")
class ShippingCheckoutIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void multiBrandOrder_createsOnePerBrandShippingSnapshot_withCorrectAmounts() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(b.brand()).shippingCost(new BigDecimal("6.99")).currency("EUR").build());

        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1), item(listingB, 1))));

        List<Map<String, Object>> snapshots = jdbc.queryForList(
                "SELECT brand_partner_id, amount, calculation_method FROM order_shipping_snapshots " +
                "WHERE order_id = ? ORDER BY brand_partner_id", oid);
        assertThat(snapshots).hasSize(2);

        Map<Long, BigDecimal> amountByBrand = new HashMap<>();
        for (Map<String, Object> row : snapshots) {
            amountByBrand.put(((Number) row.get("brand_partner_id")).longValue(), (BigDecimal) row.get("amount"));
            assertThat(row.get("calculation_method")).isEqualTo("BRAND_FLAT_RATE");
        }
        assertThat(amountByBrand.get(a.brand().getId())).isEqualByComparingTo("4.99");
        assertThat(amountByBrand.get(b.brand().getId())).isEqualByComparingTo("6.99");

        // Order total = 100 + 50 (products) + 4.99 + 6.99 (shipping) = 161.98.
        Map<String, Object> order = orderRow(oid);
        assertThat((BigDecimal) order.get("shipping_total")).isEqualByComparingTo("11.98");
        assertThat((BigDecimal) order.get("total")).isEqualByComparingTo("161.98");
    }

    @Test
    void brandWithNoProfile_fallsBackToGlobalDefaultRate() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18"); // no BrandShippingProfile row
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));

        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT amount, calculation_method FROM order_shipping_snapshots WHERE order_id = ?", oid);
        assertThat((BigDecimal) snapshot.get("amount")).isEqualByComparingTo("4.99"); // enunas.shipping.default-rate
        assertThat(snapshot.get("calculation_method")).isEqualTo("GLOBAL_DEFAULT");
    }

    @Test
    void legacyOrderWithNoShippingSnapshots_stillReadableViaApi() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));

        // Simulate a pre-feature order: delete its shipping snapshot rows directly.
        jdbc.update("DELETE FROM order_shipping_snapshots WHERE order_id = ?", oid);

        var resp = rest.exchange("/orders/" + oid, HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) resp.getBody().get("shippingSnapshots")).isEmpty();
    }
}
