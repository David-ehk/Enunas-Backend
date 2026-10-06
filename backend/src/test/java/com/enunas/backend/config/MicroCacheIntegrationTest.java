package com.enunas.backend.config;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL is 1h so nothing expires mid-test. The cache outlives each test's TRUNCATE ... RESTART IDENTITY
 * (product id 1 recurs), so every test uses its own ?t= query string to get a private cache key.
 */
@TestPropertySource(properties = {"app.micro-cache.enabled=true", "app.micro-cache.ttl=1h"})
class MicroCacheIntegrationTest extends AbstractDiscountIntegrationTest {

    private long seedProduct() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.15");
        long listingId = seedListing(brand.brand(), brand.user(), "20.00", 5);
        return jdbc.queryForObject("SELECT product_id FROM listings WHERE id = ?", Long.class, listingId);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void anonymousRead_isServedFromCache_authenticatedReadIsNot() {
        long productId = seedProduct();
        String url = "/products/" + productId + "?t=cache";

        String original = (String) rest.getForObject(url, Map.class).get("name");
        jdbc.update("UPDATE products SET name = 'Renamed' WHERE id = ?", productId);

        assertThat(rest.getForObject(url, Map.class).get("name")).isEqualTo(original);

        String token = login("acme@it.local", "Brand123!");
        Map fresh = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class).getBody();
        assertThat(fresh.get("name")).isEqualTo("Renamed");
    }

    @Test
    void notFound_isNotCached() {
        long productId = seedProduct();
        String url = "/products/" + productId + "?t=404";
        jdbc.update("UPDATE listings SET active = false WHERE product_id = ?", productId);

        assertThat(rest.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(404);

        jdbc.update("UPDATE listings SET active = true WHERE product_id = ?", productId);
        assertThat(rest.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(200);
    }
}
