package com.enunas.backend.product.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.productlisting.ProductListing;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /products (and category/color-family/search/PDP) used to show a product regardless of whether
 * any of its listings were active — a brand deactivating its only listing left the product fully
 * browsable, with a null/zero price and (per the frontend) still addable to the basket, even though
 * the actual checkout guard (OrderService.resolveAndValidateListings) already rejected the inactive
 * listing at purchase time. Fixed by gating every storefront read on "at least one currently-active
 * listing exists" — see ProductRepository (list endpoints) and ProductService.assertBrowsable (PDP).
 */
class StorefrontListingGateIntegrationTest extends AbstractDiscountIntegrationTest {

    private Product deactivateOnlyListing(BrandPartner brand, long listingId) {
        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        listing.setActive(false);
        productListingRepository.save(listing);
        return listing.getProduct();
    }

    private String productName(long productId) {
        return jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, productId);
    }

    private String productCategory(long productId) {
        return jdbc.queryForObject("SELECT category FROM products WHERE id = ?", String.class, productId);
    }

    private String productSlug(long productId) {
        return jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, productId);
    }

    @Test
    void productListing_excludesProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);

        ResponseEntity<Map> resp = rest.getForEntity("/products", Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).extracting(p -> ((Number) p.get("id")).longValue())
                .doesNotContain(product.getId());
    }

    @Test
    void productListing_stillIncludesProductWithAnActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = productListingRepository.findById(listingId).orElseThrow().getProduct();

        ResponseEntity<Map> resp = rest.getForEntity("/products", Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).extracting(p -> ((Number) p.get("id")).longValue())
                .contains(product.getId());
    }

    @Test
    void categoryBrowse_excludesProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);
        String category = productCategory(product.getId());

        ResponseEntity<Map> resp = rest.getForEntity("/products/category/" + category, Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).extracting(p -> ((Number) p.get("id")).longValue())
                .doesNotContain(product.getId());
    }

    @Test
    void search_excludesProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);
        String name = productName(product.getId());

        ResponseEntity<Map> resp = rest.getForEntity("/products/search?keyword=" + name, Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).extracting(p -> ((Number) p.get("id")).longValue())
                .doesNotContain(product.getId());
    }

    @Test
    void colorFamilyBrowse_excludesProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);
        String colorFamily = jdbc.queryForObject(
                "SELECT pc.color_family FROM product_colors pc JOIN products p ON p.id = pc.product_id WHERE p.id = ?",
                String.class, product.getId());

        ResponseEntity<Map> resp = rest.getForEntity("/products/color-family/" + colorFamily, Map.class);

        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).extracting(p -> ((Number) p.get("id")).longValue())
                .doesNotContain(product.getId());
    }

    @Test
    void pdpById_returns404ForProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);

        ResponseEntity<Map> resp = rest.getForEntity("/products/" + product.getId(), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void pdpBySlug_returns404ForProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);
        String slug = productSlug(product.getId());

        ResponseEntity<Map> resp = rest.getForEntity("/products/slug/" + slug, Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    /**
     * The gate keeps unbuyable products off the storefront — it must not hide a brand's own
     * catalogue from itself. createProduct persists a product with variants but no listing at all,
     * so gating the owner too would 404 a brand on the detail page of the product it just created.
     */
    @Test
    void pdpById_ownerStillSeesTheirOwnProductWithNoActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);
        String brandToken = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> asOwner = rest.exchange("/products/" + product.getId(),
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth(brandToken)), Map.class);
        assertThat(asOwner.getStatusCode().value()).as("owner: %s", asOwner.getBody()).isEqualTo(200);

        // Anonymous storefront traffic still gets the gate.
        assertThat(rest.getForEntity("/products/" + product.getId(), Map.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    /**
     * The owner exemption keys on identity, not on merely being authenticated: a logged-in customer
     * and a logged-in DIFFERENT brand must both still hit the gate. Without this, "viewer != null"
     * would quietly weaken the gate for every signed-in visitor rather than just the product's own
     * brand — which is the whole point of gating unbuyable products off the storefront.
     */
    @Test
    void pdpById_gateStillAppliesToOtherAuthenticatedUsers() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        BrandPartner other = seedBrand("Rival", "rival", "0.15").brand();
        seedCustomer();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = deactivateOnlyListing(brand, listingId);

        String customerToken = login("customer@it.local", "Customer123!");
        String rivalToken = login("rival@it.local", "Brand123!");

        assertThat(getProduct(product.getId(), customerToken).getStatusCode().value())
                .as("logged-in customer must not bypass the gate").isEqualTo(404);
        assertThat(getProduct(product.getId(), rivalToken).getStatusCode().value())
                .as("a different brand must not bypass the gate").isEqualTo(404);

        // Sanity: the owner's own token, on the very same product, still does.
        String ownerToken = login("acme@it.local", "Brand123!");
        assertThat(getProduct(product.getId(), ownerToken).getStatusCode().value()).isEqualTo(200);

        // And an admin, who is exempt by role rather than by ownership.
        seedAdmin();
        String adminToken = login("admin@it.local", "Admin123!");
        assertThat(getProduct(product.getId(), adminToken).getStatusCode().value()).isEqualTo(200);
    }

    private ResponseEntity<Map> getProduct(long productId, String token) {
        return rest.exchange("/products/" + productId, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth(token)), Map.class);
    }

    @Test
    void pdpById_stillReturns200ForProductWithAnActiveListing() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);
        Product product = productListingRepository.findById(listingId).orElseThrow().getProduct();

        ResponseEntity<Map> resp = rest.getForEntity("/products/" + product.getId(), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }
}
