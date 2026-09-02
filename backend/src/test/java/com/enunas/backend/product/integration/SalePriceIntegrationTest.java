package com.enunas.backend.product.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storefront needs two numbers to render a sale: what the customer pays, and the price it was
 * reduced from. The API only ever returned the first, so a PDP had nothing to strike through.
 *
 * <p>{@code price} keeps its meaning — what is charged — and {@code originalPrice} is the same
 * listing's undiscounted price, present only when there is an actual reduction. "On sale" is
 * therefore exactly "originalPrice is present", with no comparison needed on the client.
 */
class SalePriceIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductListingRepository productListingRepository;

    private long productIdOfListing(long listingId) {
        return productListingRepository.findById(listingId).orElseThrow().getProduct().getId();
    }

    private void putOnSale(long listingId, String discountedEuros) {
        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        listing.setDiscountPrice(new BigDecimal(discountedEuros));
        productListingRepository.save(listing);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pdp(long productId) {
        ResponseEntity<Map> resp = rest.getForEntity("/products/" + productId, Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody();
    }

    @Test
    void productOnSale_carriesBothTheChargedPriceAndTheOneToStrikeThrough() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);
        putOnSale(listing, "59.95");

        Map<String, Object> body = pdp(productIdOfListing(listing));

        assertThat(new BigDecimal(body.get("price").toString())).isEqualByComparingTo("59.95");
        assertThat(new BigDecimal(body.get("originalPrice").toString())).isEqualByComparingTo("89.95");
    }

    @Test
    void productNotOnSale_hasNoOriginalPriceAtAll() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long listing = seedListing(brand, brand.getUser(), "89.95", 5);

        Map<String, Object> body = pdp(productIdOfListing(listing));

        assertThat(new BigDecimal(body.get("price").toString())).isEqualByComparingTo("89.95");
        // Null rather than a copy of price: the client must not have to compare the two to decide
        // whether to render a strikethrough.
        assertThat(body.get("originalPrice")).isNull();
    }

    /**
     * The case two independent MIN() aggregates get wrong. This product has a cheap listing that is
     * NOT discounted and an expensive one that is: the lowest charged price is 40.00 (undiscounted),
     * while the lowest list price across the listings is also 40.00 — but the lowest DISCOUNTED
     * price belongs to the 120.00 listing. Pairing "cheapest current" with "lowest list price"
     * would advertise a sale that does not exist on the listing being sold.
     */
    @Test
    void severalListings_pairThePriceWithItsOwnListingNotTheCheapestElsewhere() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long plainCheapListing = seedListing(brand, brand.getUser(), "40.00", 5);
        long discountedDearListing = seedListing(brand, brand.getUser(), "120.00", 5);
        putOnSale(discountedDearListing, "70.00");

        // Both listings hang off their own products from seedListing, so join them onto one product
        // to get two sellable listings for a single PDP.
        long productId = productIdOfListing(plainCheapListing);
        ProductListing dear = productListingRepository.findById(discountedDearListing).orElseThrow();
        jdbc.update("UPDATE listings SET product_id = ? WHERE id = ?", productId, dear.getId());

        Map<String, Object> body = pdp(productId);

        // 40.00 is what is charged, and it is not a sale — so nothing to strike through.
        assertThat(new BigDecimal(body.get("price").toString())).isEqualByComparingTo("40.00");
        assertThat(body.get("originalPrice")).isNull();
    }

    /** Complete-The-Look cards carry the same pair, so a sale is visible on the card too. */
    @SuppressWarnings("unchecked")
    @Test
    void completeTheLookCard_carriesTheSamePricePair() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        long mainListing = seedListing(brand, brand.getUser(), "89.95", 5);
        long relatedListing = seedListing(brand, brand.getUser(), "50.00", 5);
        putOnSale(relatedListing, "35.00");

        long mainProduct = productIdOfListing(mainListing);
        long relatedProduct = productIdOfListing(relatedListing);
        jdbc.update("UPDATE products SET complete_the_look_enabled = true WHERE id = ?", mainProduct);
        jdbc.update("INSERT INTO product_complete_the_look (product_id, related_product_id) VALUES (?, ?)",
                mainProduct, relatedProduct);

        List<Map<String, Object>> cards =
                (List<Map<String, Object>>) pdp(mainProduct).get("completeTheLookProducts");

        assertThat(cards).hasSize(1);
        assertThat(new BigDecimal(cards.get(0).get("price").toString())).isEqualByComparingTo("35.00");
        assertThat(new BigDecimal(cards.get(0).get("originalPrice").toString())).isEqualByComparingTo("50.00");
    }
}
