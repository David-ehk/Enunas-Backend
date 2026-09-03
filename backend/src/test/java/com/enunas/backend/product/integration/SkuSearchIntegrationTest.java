package com.enunas.backend.product.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Searching a SKU used to find nothing: the search predicate covered name, brand and description
 * only, and the sole SKU lookup in the codebase was an exact match on the unique column behind
 * {@code GET /products/sku/{sku}}. A customer holding a SKU from a care label or an invoice had no
 * way to reach the product from the search box, and a frontend routing the query to the exact-match
 * endpoint inherited the same limitation — one wrong character and it found nothing.
 *
 * <p>SKU is now part of the search predicate as a substring match, so a partial or mis-cased SKU
 * still lands on the product. The exact-match endpoint is unchanged, for callers that know they
 * hold a whole SKU.
 */
class SkuSearchIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductListingRepository productListingRepository;

    private long productIdOfListing(long listingId) {
        return productListingRepository.findById(listingId).orElseThrow().getProduct().getId();
    }

    private String skuOf(long productId) {
        return jdbc.queryForObject(
                "SELECT sku FROM product_colors WHERE product_id = ? LIMIT 1", String.class, productId);
    }

    /** Passes the keyword as a URI template variable so RestTemplate encodes it exactly once —
     *  concatenating a pre-encoded string re-encodes the % and the server sees the escape text. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(String keyword) {
        Map<String, Object> body = rest.getForObject("/products/search?keyword={k}", Map.class, keyword);
        return (List<Map<String, Object>>) body.get("content");
    }

    @SuppressWarnings("unchecked")
    @Test
    void exactSku_findsTheProduct() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listing);

        List<Map<String, Object>> hits = search(skuOf(productId));

        assertThat(hits).hasSize(1);
        assertThat(((Number) hits.get(0).get("id")).longValue()).isEqualTo(productId);
    }

    /** The case the exact-match endpoint could never serve: someone typed most of the SKU. */
    @SuppressWarnings("unchecked")
    @Test
    void truncatedSku_stillFindsTheProduct() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listing);

        String partial = skuOf(productId).substring(0, 5);
        List<Map<String, Object>> hits = search(partial);

        assertThat(hits).extracting(h -> ((Number) h.get("id")).longValue()).contains(productId);
    }

    /** SKUs get read off labels and retyped in whatever case comes out. */
    @SuppressWarnings("unchecked")
    @Test
    void lowercasedSku_findsTheProduct() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listing);

        List<Map<String, Object>> hits = search(skuOf(productId).toLowerCase());

        assertThat(hits).hasSize(1);
        assertThat(((Number) hits.get(0).get("id")).longValue()).isEqualTo(productId);
    }

    /** Pasted values carry whitespace; the service trims before matching. */
    @SuppressWarnings("unchecked")
    @Test
    void skuPastedWithSurroundingWhitespace_findsTheProduct() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listing);

        List<Map<String, Object>> hits = search("  " + skuOf(productId) + " ");

        assertThat(hits).hasSize(1);
        assertThat(((Number) hits.get(0).get("id")).longValue()).isEqualTo(productId);
    }

    /**
     * The storefront gate still applies. A SKU from an old care label whose product is no longer
     * sellable must not resurface it — search is a storefront read, not a lookup tool.
     */
    @SuppressWarnings("unchecked")
    @Test
    void skuOfAnUnsellableProduct_isStillNotFound() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listingId = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listingId);
        String sku = skuOf(productId);

        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        listing.setActive(false);
        productListingRepository.save(listing);

        assertThat(search(sku)).isEmpty();
    }

    /** Name and description matching is untouched by the added clause. */
    @SuppressWarnings("unchecked")
    @Test
    void nameSearch_stillWorks() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listing);

        String name = jdbc.queryForObject(
                "SELECT name FROM products WHERE id = ?", String.class, productId);

        assertThat(search(name))
                .extracting(h -> ((Number) h.get("id")).longValue())
                .contains(productId);
    }
}
