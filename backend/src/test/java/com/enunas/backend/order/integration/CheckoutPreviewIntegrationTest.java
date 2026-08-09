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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class CheckoutPreviewIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void preview_matchesWhatCreateOrderWouldCharge_andPersistsNothing() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> address = Map.of(
                "firstName", "John", "lastName", "Doe",
                "street", "Hauptstrasse", "houseNumber", "1",
                "city", "Berlin", "postalCode", "10115", "country", "DE");
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listingA, 1)));
        body.put("shippingAddress", address);

        long ordersBefore = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);

        ResponseEntity<Map> resp = rest.exchange("/orders/preview", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map respBody = resp.getBody();
        assertThat(new BigDecimal(String.valueOf(respBody.get("subtotal")))).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(respBody.get("shippingTotal")))).isEqualByComparingTo("4.99");
        assertThat(new BigDecimal(String.valueOf(respBody.get("total")))).isEqualByComparingTo("104.99");

        long ordersAfter = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        assertThat(ordersAfter).isEqualTo(ordersBefore); // preview persists nothing

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));
        assertThat((BigDecimal) orderRow(oid).get("total")).isEqualByComparingTo("104.99");
    }

    /**
     * Regression guard: the preview used to skip discount computation entirely (because the only
     * computation path also reserved usage), so it quoted a HIGHER total than checkout charged.
     * It must now show the real discount while still never incrementing used_count.
     */
    @Test
    void preview_showsSuppliedDiscount_withoutEverReservingUsage() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "119.00", 10); // gross 119 → net 100
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> created = createAdminDiscount(adminToken,
                Map.of("code", "PREVIEW10", "percent", 0.10));
        assertThat(created.getStatusCode().is2xxSuccessful()).as("create: %s", created.getBody()).isTrue();
        assertThat(usedCount("PREVIEW10")).isZero();

        Map<String, Object> address = Map.of(
                "firstName", "John", "lastName", "Doe",
                "street", "Hauptstrasse", "houseNumber", "1",
                "city", "Berlin", "postalCode", "10115", "country", "DE");
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listingA, 1)));
        body.put("shippingAddress", address);
        body.put("discountCode", "PREVIEW10");

        // Preview twice — a repeatable, non-committal call must stay free of side effects.
        ResponseEntity<Map> resp = null;
        for (int i = 0; i < 2; i++) {
            resp = rest.exchange("/orders/preview", HttpMethod.POST,
                    new HttpEntity<>(body, auth(custToken)), Map.class);
            assertThat(resp.getStatusCode().value()).as("preview: %s", resp.getBody()).isEqualTo(200);
        }

        // ADMIN 10% on net 100 → 11.90 GROSS reduction. 119.00 − 11.90 + 4.99 shipping = 112.09.
        Map respBody = resp.getBody();
        assertThat(respBody.get("discountCode")).isEqualTo("PREVIEW10");
        assertThat(new BigDecimal(String.valueOf(respBody.get("subtotal")))).isEqualByComparingTo("119.00");
        assertThat(new BigDecimal(String.valueOf(respBody.get("discountAmount"))))
                .as("preview must reflect the real discount, not zero")
                .isEqualByComparingTo("11.90");
        assertThat(new BigDecimal(String.valueOf(respBody.get("shippingTotal")))).isEqualByComparingTo("4.99");
        assertThat(new BigDecimal(String.valueOf(respBody.get("total")))).isEqualByComparingTo("112.09");

        // Never reserved by previewing, no matter how many times.
        assertThat(usedCount("PREVIEW10")).as("preview must not burn a usage").isZero();

        // The real order charges exactly what the preview quoted, and reserves exactly one usage.
        long oid = orderId(postOrder(custToken, "PREVIEW10", List.of(item(listingA, 1))));
        assertThat((BigDecimal) orderRow(oid).get("total")).isEqualByComparingTo("112.09");
        assertThat((BigDecimal) orderRow(oid).get("discount_amount")).isEqualByComparingTo("11.90");
        assertThat(usedCount("PREVIEW10")).isEqualTo(1);
    }
}
