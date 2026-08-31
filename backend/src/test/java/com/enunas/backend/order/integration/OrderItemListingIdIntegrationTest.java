package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * items[].listingId in both POST /orders/preview and every persisted order response was silently
 * the variant id — OrderItem never stored the listing id the buyer actually ordered against, only
 * variant_id (listings are deletable/deactivatable without touching order history, so the entity
 * deliberately never held a live FK to one). Any client resolving a returned line back to its
 * listing read the wrong row, with no error — the ids were merely plausible, not obviously wrong.
 * Fixed by OrderItem.listingIdSnapshot, frozen at order-creation time (V28).
 */
class OrderItemListingIdIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void preview_returnsTheRealListingId_notTheVariantId() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        seedCustomer();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        String customerToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = rest.exchange("/orders/preview", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "items", List.of(item(listingId, 1)),
                        "shippingAddress", Map.of(
                                "firstName", "John", "lastName", "Doe",
                                "street", "Hauptstrasse", "houseNumber", "1",
                                "city", "Berlin", "postalCode", "10115", "country", "DE")),
                        auth(customerToken)),
                Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(((Number) items.get(0).get("listingId")).longValue()).isEqualTo(listingId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistedOrder_returnsTheRealListingId_notTheVariantId() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        seedCustomer();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        String customerToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingId, 1)));

        assertThat(order.getStatusCode().value()).isEqualTo(201);
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(((Number) items.get(0).get("listingId")).longValue()).isEqualTo(listingId);

        // The real, persisted column — not just what the response happens to say.
        Long persisted = jdbc.queryForObject(
                "SELECT listing_id_snapshot FROM order_items WHERE id = ?",
                Long.class, ((Number) items.get(0).get("id")).longValue());
        assertThat(persisted).isEqualTo(listingId);
    }
}
