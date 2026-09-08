package com.enunas.backend.product.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.productlisting.ProductListing;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A product with a future {@code Product.releaseDate} is a public "preview": returned by every
 * storefront read carrying {@code preview=true} and no price, but never purchasable. {@code releaseDate}
 * is the master switch — it overrides the listing's own availability window.
 *
 * <p>See docs/superpowers/specs/2026-09-08-product-preview-state-design.md.
 */
class ProductPreviewStateIntegrationTest extends AbstractDiscountIntegrationTest {

    private record Fixture(BrandFixture brand, Product product, ProductListing listing) {}

    /** A normal sellable product, then a future releaseDate stamped on it. */
    private Fixture seedPreview(LocalDate releaseDate) {
        BrandFixture brand = seedBrand("Acme", "acme", "0.15");
        long listingId = seedListing(brand.brand(), brand.user(), "89.00", 5);
        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        Product product = productRepository.findById(listing.getProduct().getId()).orElseThrow();
        product.setReleaseDate(releaseDate);
        productRepository.saveAndFlush(product);
        return new Fixture(brand, product, listing);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageContent(ResponseEntity<Map> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("content");
    }

    private Map<String, Object> productInPage(ResponseEntity<Map> resp, long productId) {
        return pageContent(resp).stream()
                .filter(p -> ((Number) p.get("id")).longValue() == productId)
                .findFirst().orElse(null);
    }

    private String slug(long productId) {
        return jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, productId);
    }

    private String name(long productId) {
        return jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, productId);
    }

    private String category(long productId) {
        return jdbc.queryForObject("SELECT category FROM products WHERE id = ?", String.class, productId);
    }

    private String colorFamily(long productId) {
        return jdbc.queryForObject(
                "SELECT color_family FROM product_colors WHERE product_id = ?", String.class, productId);
    }

    // ── PLP feed ────────────────────────────────────────────────────────────────────────────

    @Test
    void productList_includesFutureReleaseProduct_flaggedPreview_withoutPrice() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));

        Map<String, Object> row = productInPage(rest.getForEntity("/products", Map.class), f.product().getId());

        assertThat(row).as("preview product is listed").isNotNull();
        assertThat(row.get("preview")).isEqualTo(true);
        assertThat(row.get("price")).isNull();
        assertThat(row.get("originalPrice")).isNull();
    }

    @Test
    void productList_stillOmitsFutureReleaseProduct_whenItHasNoActiveListing() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        f.listing().setActive(false);
        productListingRepository.saveAndFlush(f.listing());

        assertThat(productInPage(rest.getForEntity("/products", Map.class), f.product().getId()))
                .as("no active listing → hidden, exactly as today").isNull();
    }

    @Test
    void productList_previewWins_evenWhenTheListingWindowIsAlreadyOpen() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        f.listing().setAvailableFrom(LocalDateTime.now().minusDays(1));
        productListingRepository.saveAndFlush(f.listing());

        Map<String, Object> row = productInPage(rest.getForEntity("/products", Map.class), f.product().getId());

        assertThat(row).isNotNull();
        assertThat(row.get("preview")).isEqualTo(true);
        assertThat(row.get("price")).as("master switch: no price before releaseDate").isNull();
    }

    @Test
    void productList_pastReleaseDate_behavesExactlyAsBefore() {
        Fixture f = seedPreview(LocalDate.now().minusDays(1));

        Map<String, Object> row = productInPage(rest.getForEntity("/products", Map.class), f.product().getId());

        assertThat(row).isNotNull();
        assertThat(row.get("preview")).isEqualTo(false);
        assertThat(row.get("price")).as("released → priced as normal").isNotNull();
    }

    @Test
    void search_category_colorFamily_allSurfaceThePreviewProduct() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        long id = f.product().getId();

        assertThat(productInPage(rest.getForEntity("/products/search?keyword=" + name(id), Map.class), id))
                .as("search").isNotNull();
        assertThat(productInPage(rest.getForEntity("/products/category/" + category(id), Map.class), id))
                .as("category browse").isNotNull();
        assertThat(productInPage(rest.getForEntity("/products/color-family/" + colorFamily(id), Map.class), id))
                .as("color-family browse").isNotNull();
    }

    // ── PDP ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void pdpBySlug_returns200_flaggedPreview_withoutPrice() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));

        ResponseEntity<Map> resp = rest.getForEntity("/products/slug/" + slug(f.product().getId()), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("preview")).isEqualTo(true);
        assertThat(resp.getBody().get("price")).isNull();
    }

    @Test
    void pdpBySlug_stillReturns404_whenFutureReleaseProductHasNoActiveListing() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        f.listing().setActive(false);
        productListingRepository.saveAndFlush(f.listing());

        assertThat(rest.getForEntity("/products/slug/" + slug(f.product().getId()), Map.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void productListingsEndpoint_returnsNothingForAPreviewProduct() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        f.listing().setAvailableFrom(LocalDateTime.now().minusDays(1)); // window open — only releaseDate blocks
        productListingRepository.saveAndFlush(f.listing());

        ResponseEntity<List> resp = rest.getForEntity(
                "/products/" + f.product().getId() + "/listings", List.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).as("no listing/price data leaks before launch").isEmpty();
    }

    @Test
    void singleListingEndpoint_is404ForAPreviewProduct_evenWithAnOpenWindow() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        f.listing().setAvailableFrom(LocalDateTime.now().minusDays(1));
        productListingRepository.saveAndFlush(f.listing());

        assertThat(rest.getForEntity("/listings/" + f.listing().getId(), Map.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    // ── Checkout ────────────────────────────────────────────────────────────────────────────

    @Test
    void checkout_rejectsAPreviewProduct_evenWhenTheListingWindowIsOpen() {
        Fixture f = seedPreview(LocalDate.now().plusDays(30));
        f.listing().setAvailableFrom(LocalDateTime.now().minusDays(1));
        productListingRepository.saveAndFlush(f.listing());
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = postOrder(token, null, List.of(item(f.listing().getId(), 1)));

        assertThat(resp.getStatusCode().value())
                .as("IllegalStateException → 409: %s", resp.getBody()).isEqualTo(409);
    }

    @Test
    void checkout_succeedsOnceTheProductIsReleased() {
        Fixture f = seedPreview(LocalDate.now().minusDays(1));
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = postOrder(token, null, List.of(item(f.listing().getId(), 1)));

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("released product is buyable: %s", resp.getBody()).isTrue();
    }
}
