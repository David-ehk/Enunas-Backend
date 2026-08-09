package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class AdminShippingProfileIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void adminSetsShippingProfile_thenCheckoutUsesIt() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/admin/brands/" + a.brand().getId() + "/shipping-profile",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("shippingCost", 6.99, "originCountry", "DE"), auth(adminToken)),
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));

        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM order_shipping_snapshots WHERE order_id = ?", BigDecimal.class, oid);
        assertThat(amount).isEqualByComparingTo("6.99");

        BigDecimal storedCost = jdbc.queryForObject(
                "SELECT shipping_cost FROM brand_shipping_profiles WHERE brand_id = ?",
                BigDecimal.class, a.brand().getId());
        assertThat(storedCost).isEqualByComparingTo("6.99");
    }

    @Test
    void adminSetsExplicitFreeShipping_zeroIsDistinctFromUnset() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        rest.exchange("/admin/brands/" + a.brand().getId() + "/shipping-profile", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("shippingCost", 0.00), auth(adminToken)), Map.class);

        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));

        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT amount, calculation_method FROM order_shipping_snapshots WHERE order_id = ?", oid);
        assertThat((BigDecimal) snapshot.get("amount")).isEqualByComparingTo("0.00");
        assertThat(snapshot.get("calculation_method")).isEqualTo("BRAND_FREE_SHIPPING");
    }

    /**
     * Pins the FULL-REPLACE contract of {@code PATCH /admin/brands/{id}/shipping-profile}.
     *
     * <p>Despite the PATCH verb this endpoint is not a partial update: {@code SetShippingProfileDto}
     * cannot distinguish "field absent from JSON" from "field explicitly null", so an omitted field
     * arrives as {@code null} and CLEARS the stored value. A second PATCH carrying only
     * {@code avgShippingDays} therefore wipes a previously configured {@code shippingCost}, and the
     * brand falls back to the platform GLOBAL_DEFAULT rate.
     *
     * <p>This test exists so that behaviour is an intentional, visible contract rather than a silent
     * surprise — and so any future move to real presence-tracking has to fail here first, loudly,
     * instead of quietly changing what admins' existing requests do. See
     * {@code AdminService#setBrandShippingProfile}'s javadoc.
     */
    @Test
    void patchOmittingShippingCost_clearsIt_fullReplaceContract() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        // 1. Configure a real flat rate.
        ResponseEntity<Map> first = rest.exchange(
                "/admin/brands/" + a.brand().getId() + "/shipping-profile", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("shippingCost", 9.99, "originCountry", "DE",
                        "avgShippingDays", 2), auth(adminToken)), Map.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT shipping_cost FROM brand_shipping_profiles WHERE brand_id = ?",
                BigDecimal.class, a.brand().getId())).isEqualByComparingTo("9.99");

        // 2. Second PATCH sends ONLY avgShippingDays — shippingCost and originCountry are omitted.
        ResponseEntity<Map> second = rest.exchange(
                "/admin/brands/" + a.brand().getId() + "/shipping-profile", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("avgShippingDays", 5), auth(adminToken)), Map.class);
        assertThat(second.getStatusCode().value()).isEqualTo(200);

        // 3. Full-replace: the omitted fields are now NULL, the sent one took effect.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT shipping_cost, origin_country, avg_shipping_days " +
                "FROM brand_shipping_profiles WHERE brand_id = ?", a.brand().getId());
        assertThat(row.get("shipping_cost")).as("omitted field is cleared, not preserved").isNull();
        assertThat(row.get("origin_country")).isNull();
        assertThat(row.get("avg_shipping_days")).isEqualTo(5);

        // 4. Consequence: the brand now bills the platform GLOBAL_DEFAULT rate, not its own 9.99.
        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT amount, calculation_method FROM order_shipping_snapshots WHERE order_id = ?", oid);
        assertThat((BigDecimal) snapshot.get("amount")).isEqualByComparingTo("4.99");
        assertThat(snapshot.get("calculation_method")).isEqualTo("GLOBAL_DEFAULT");
    }

    /**
     * Guards the entry point where a negative shipping cost would otherwise reach the system:
     * an admin PATCH. {@code SetShippingProfileDto.shippingCost} carries {@code @DecimalMin("0.00")}
     * — this pins that it's actually wired up ({@code @Valid} on the controller) and that a rejected
     * request never persists anything, rather than trusting the annotation is enforced.
     */
    @Test
    void adminAttemptsNegativeShippingCost_rejectedWithoutPersisting() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/admin/brands/" + a.brand().getId() + "/shipping-profile",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("shippingCost", -5.00), auth(adminToken)),
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM brand_shipping_profiles WHERE brand_id = ?",
                Integer.class, a.brand().getId());
        assertThat(count).isEqualTo(0);
    }
}
