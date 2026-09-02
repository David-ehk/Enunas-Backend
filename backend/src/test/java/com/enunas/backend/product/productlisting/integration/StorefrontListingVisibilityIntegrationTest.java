package com.enunas.backend.product.productlisting.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductStatus;
import com.enunas.backend.product.productlisting.ProductListing;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listing-shaped public reads must apply the same gate the rest of the storefront does —
 * "product status ACTIVE <em>and</em> a currently-sellable listing", the pairing spelled out on the
 * PLP queries in ProductRepository and enforced for a single product by
 * ProductService.assertBrowsable.
 *
 * <p>They did not. {@code GET /listings/{id}} was an ungated findById, and neither
 * {@code GET /listings} nor {@code GET /products/{id}/listings} looked at products.status at all,
 * so an anonymous caller could read the name, SKU, live stock, full price breakdown and launch
 * timestamps of a product an admin had suspended or that had not been released yet. The frontend
 * was compensating for part of this client-side (see {@code sellableOnly} in productApi.ts), which
 * is not a gate — it only hides what the API already handed over.
 *
 * <p>The owning brand and admins stay exempt, exactly as in assertBrowsable: the gate keeps
 * unbuyable products off the storefront, it does not hide a brand's own catalogue from it.
 */
class StorefrontListingVisibilityIntegrationTest extends AbstractDiscountIntegrationTest {

    private record Fixture(BrandFixture brand, Product product, ProductListing listing) {}

    private Fixture seedSellable() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.15");
        long listingId = seedListing(brand.brand(), brand.user(), "100.00", 5);
        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        // listing.getProduct() is a LAZY proxy and there is no session out here; go via the id.
        Product product = productRepository.findById(listing.getProduct().getId()).orElseThrow();
        return new Fixture(brand, product, listing);
    }

    private Fixture seedHidden(ProductStatus status) {
        Fixture f = seedSellable();
        f.product().setStatus(status);
        productRepository.saveAndFlush(f.product());
        return f;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageContent(ResponseEntity<Map> resp) {
        return (List<Map<String, Object>>) resp.getBody().get("content");
    }

    // ── GET /listings ────────────────────────────────────────────────────────────────────────

    /** The regression: omitting the optional filter used to bind SQL {@code region = NULL}, which
     *  matches nothing, so the default form of this endpoint always answered with an empty list. */
    @Test
    void listingFeedWithoutRegion_returnsSellableListings() {
        Fixture f = seedSellable();

        ResponseEntity<Map> resp = rest.getForEntity("/listings", Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(pageContent(resp))
                .extracting(row -> ((Number) row.get("id")).longValue())
                .contains(f.listing().getId());
    }

    /** A listing with no region of its own is sold everywhere, so it matches any region asked for. */
    @Test
    void listingFeedWithRegion_includesGloballySoldListings() {
        Fixture f = seedSellable();
        assertThat(f.listing().getRegion()).isNull();

        ResponseEntity<Map> resp = rest.getForEntity("/listings?region=DE", Map.class);

        assertThat(pageContent(resp))
                .extracting(row -> ((Number) row.get("id")).longValue())
                .contains(f.listing().getId());
    }

    @Test
    void listingFeed_excludesSuspendedProducts() {
        Fixture f = seedHidden(ProductStatus.SUSPENDED);

        ResponseEntity<Map> resp = rest.getForEntity("/listings", Map.class);

        assertThat(pageContent(resp))
                .extracting(row -> ((Number) row.get("id")).longValue())
                .doesNotContain(f.listing().getId());
    }

    @Test
    void listingFeed_isPaged() {
        seedSellable();

        ResponseEntity<Map> resp = rest.getForEntity("/listings?size=1", Map.class);

        assertThat(resp.getBody()).containsKeys("content", "totalElements", "size");
        assertThat(pageContent(resp)).hasSizeLessThanOrEqualTo(1);
    }

    // ── GET /listings/{id} ───────────────────────────────────────────────────────────────────

    @Test
    void singleListing_ofSuspendedProduct_is404ForAnonymous() {
        Fixture f = seedHidden(ProductStatus.SUSPENDED);

        ResponseEntity<Map> resp = rest.getForEntity("/listings/" + f.listing().getId(), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    /** An unreleased drop is the same leak in a different shape: the window has not opened yet. */
    @Test
    void singleListing_ofUnreleasedDrop_is404ForAnonymous() {
        Fixture f = seedSellable();
        f.listing().setAvailableFrom(java.time.LocalDateTime.now().plusDays(7));
        productListingRepository.saveAndFlush(f.listing());

        ResponseEntity<Map> resp = rest.getForEntity("/listings/" + f.listing().getId(), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void singleListing_ofSuspendedProduct_isVisibleToTheOwningBrand() {
        Fixture f = seedHidden(ProductStatus.SUSPENDED);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = rest.exchange("/listings/" + f.listing().getId(),
                HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) resp.getBody().get("id")).longValue()).isEqualTo(f.listing().getId());
    }

    @Test
    void singleListing_ofSellableProduct_staysPubliclyReadable() {
        Fixture f = seedSellable();

        ResponseEntity<Map> resp = rest.getForEntity("/listings/" + f.listing().getId(), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    // ── GET /products/{id}/listings ──────────────────────────────────────────────────────────

    /** Empty, not 404: the PDP deliberately keeps rendering and shows "price unavailable". */
    @Test
    void productListings_ofSuspendedProduct_areEmptyForAnonymous() {
        Fixture f = seedHidden(ProductStatus.SUSPENDED);

        ResponseEntity<List> resp = rest.getForEntity(
                "/products/" + f.product().getId() + "/listings", List.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    void productListings_ofSuspendedProduct_stayVisibleToTheOwningBrand() {
        Fixture f = seedHidden(ProductStatus.SUSPENDED);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<List> resp = rest.exchange("/products/" + f.product().getId() + "/listings",
                HttpMethod.GET, new HttpEntity<>(auth(token)), List.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    void productListings_ofSellableProduct_stayPubliclyReadable() {
        Fixture f = seedSellable();

        ResponseEntity<List> resp = rest.getForEntity(
                "/products/" + f.product().getId() + "/listings", List.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    // ── The brand's own management view ──────────────────────────────────────────────────────

    /**
     * A brand builds a drop before it opens, so the owner read must not apply the availability
     * window — {@code findByProductIdAndActive} deliberately checks the active flag only. Anonymous
     * traffic still gets nothing until the window opens.
     */
    @Test
    void productListings_ofFutureDatedDrop_stayVisibleToTheOwningBrand() {
        Fixture f = seedSellable();
        f.listing().setAvailableFrom(LocalDateTime.now().plusDays(14));
        productListingRepository.saveAndFlush(f.listing());
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<List> ownerResp = rest.exchange("/products/" + f.product().getId() + "/listings",
                HttpMethod.GET, new HttpEntity<>(auth(token)), List.class);
        ResponseEntity<List> anonResp = rest.getForEntity(
                "/products/" + f.product().getId() + "/listings", List.class);

        assertThat(ownerResp.getBody()).as("the brand manages its unopened drop").hasSize(1);
        assertThat(anonResp.getBody()).as("the storefront waits for the window").isEmpty();
    }

    /**
     * The gap this pair documents: {@code active = false} hides a listing from its own brand too.
     * {@code getActiveListingsByProduct} sends the owner to {@code findByProductIdAndActive(id, true)},
     * so a brand that deactivates a listing can no longer see it on this endpoint — the repository's
     * unfiltered {@code findByProductId} is labelled "the brand-facing management view" but the
     * service method that uses it ({@code getListingsByProduct}) is not wired to any route.
     */
    @Test
    void productListings_ofDeactivatedListing_areHiddenFromTheOwningBrandToo() {
        Fixture f = seedSellable();
        f.listing().setActive(false);
        productListingRepository.saveAndFlush(f.listing());
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<List> resp = rest.exchange("/products/" + f.product().getId() + "/listings",
                HttpMethod.GET, new HttpEntity<>(auth(token)), List.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEmpty();
    }
}
