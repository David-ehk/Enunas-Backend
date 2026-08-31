package com.enunas.backend.product.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complete-The-Look renders each referenced product as a card with its own price, and a referenced
 * product does not necessarily have a sellable listing — it may never have had one (listings are
 * created separately from the product, which is why the PDP gate exempts the owner), or its only
 * listing may since have been deactivated.
 *
 * <p>That case used to crash. The CTL prices were collected with
 * {@code Collectors.toMap(id, lowestPrice().orElse(null), ...)}, and toMap is backed by
 * {@code HashMap.merge}, which throws NullPointerException on a null value rather than storing one
 * — so a single unpriced CTL target turned the whole response into a 500, on the detail page and on
 * createProduct alike. These tests pin the behaviour the DTO always intended: no price is a null
 * price, not an error.
 */
class CompleteTheLookPriceIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductListingRepository productListingRepository;

    private long productIdOfListing(long listingId) {
        return productListingRepository.findById(listingId).orElseThrow().getProduct().getId();
    }

    private void deactivate(long listingId) {
        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        listing.setActive(false);
        productListingRepository.save(listing);
    }

    private void linkCompleteTheLook(long productId, long relatedProductId) {
        jdbc.update("UPDATE products SET complete_the_look_enabled = true WHERE id = ?", productId);
        jdbc.update("INSERT INTO product_complete_the_look (product_id, related_product_id) VALUES (?, ?)",
                productId, relatedProductId);
    }

    @SuppressWarnings("unchecked")
    @Test
    void productDetail_rendersWhenACompleteTheLookTargetHasNoSellableListing() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long mainListing = seedListing(brand, brand.getUser(), "89.95", 5);
        long relatedListing = seedListing(brand, brand.getUser(), "29.95", 5);
        long mainProduct = productIdOfListing(mainListing);
        long relatedProduct = productIdOfListing(relatedListing);

        linkCompleteTheLook(mainProduct, relatedProduct);
        // The referenced product is no longer sellable, so it has no price to show.
        deactivate(relatedListing);

        ResponseEntity<Map> resp = rest.getForEntity("/products/" + mainProduct, Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("id")).isEqualTo((int) mainProduct);
    }

    /** The priced case still carries the price through, so the fix did not simply drop CTL prices. */
    @SuppressWarnings("unchecked")
    @Test
    void productDetail_stillCarriesTheCompleteTheLookPriceWhenTheTargetIsSellable() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long mainListing = seedListing(brand, brand.getUser(), "89.95", 5);
        long relatedListing = seedListing(brand, brand.getUser(), "29.95", 5);
        long mainProduct = productIdOfListing(mainListing);
        long relatedProduct = productIdOfListing(relatedListing);

        linkCompleteTheLook(mainProduct, relatedProduct);

        ResponseEntity<Map> resp = rest.getForEntity("/products/" + mainProduct, Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> ctl =
                (List<Map<String, Object>>) resp.getBody().get("completeTheLookProducts");
        assertThat(ctl).hasSize(1);
        assertThat(ctl.get(0).get("id")).isEqualTo((int) relatedProduct);
        assertThat(ctl.get(0).get("price").toString()).startsWith("29.95");
    }

    /** The browse list takes the same mapping path, with prices batched for the whole page. */
    @SuppressWarnings("unchecked")
    @Test
    void browseList_rendersWhenACompleteTheLookTargetHasNoSellableListing() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long mainListing = seedListing(brand, brand.getUser(), "89.95", 5);
        long relatedListing = seedListing(brand, brand.getUser(), "29.95", 5);
        linkCompleteTheLook(productIdOfListing(mainListing), productIdOfListing(relatedListing));
        deactivate(relatedListing);

        ResponseEntity<Map> resp = rest.getForEntity("/products", Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        // The deactivated product drops out of the list; the one referencing it still renders.
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("id")).isEqualTo((int) productIdOfListing(mainListing));
    }

    /** A product that has been ordered must refuse deletion with a message that says what to do. */
    @SuppressWarnings("unchecked")
    @Test
    void deletingAnOrderedProduct_isRefusedWithAnActionableMessage() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        seedCustomer();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        long productId = productIdOfListing(listing);

        String customerToken = login("customer@it.local", "Customer123!");
        String brandToken = login("alpha@it.local", "Brand123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listing, 1)));
        confirmPaid(((Number) order.getBody().get("id")).longValue());

        ResponseEntity<Map> resp = rest.exchange("/products/delete/" + productId, HttpMethod.DELETE,
                new HttpEntity<>(auth(brandToken)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("message").toString()).contains("ARCHIVED");
        // And the product is still there, with its order history intact.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(1);
    }
}
