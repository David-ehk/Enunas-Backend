package com.enunas.backend.product.productlisting.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.product.productlisting.ProductListing;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The availability window on a listing is two nullable timestamps, and nothing defaults them:
 * ProductListingService.createListing passes {@code dto.getAvailableFrom()} straight through, and
 * the shared test fixture builds listings without one. A NULL bound therefore means "no bound", the
 * reading {@link ProductListing#isCurrentlyActive()} has always used.
 *
 * <p>Two repository queries used to spell the lower bound as a bare
 * {@code l.availableFrom <= CURRENT_TIMESTAMP}, with no NULL guard, so they matched only listings
 * that had been given an explicit start date — which is to say almost none. Both were unused at the
 * time, which is exactly why it went unnoticed; these tests pin the predicate before anything calls
 * them.
 */
class ProductListingWindowIntegrationTest extends AbstractDiscountIntegrationTest {

    private ProductListing seedPlainListing() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.15");
        long listingId = seedListing(brand.brand(), brand.user(), "100.00", 5);
        ProductListing listing = productListingRepository.findById(listingId).orElseThrow();
        assertThat(listing.getAvailableFrom())
                .as("fixture must reproduce the ordinary case: no explicit start date")
                .isNull();
        return listing;
    }

    @Test
    void listingWithNoStartDate_isCurrentlySellableByVariant() {
        ProductListing listing = seedPlainListing();

        assertThat(productListingRepository.findCurrentlyActiveByVariantId(listing.getVariant().getId()))
                .get()
                .extracting(ProductListing::getId)
                .isEqualTo(listing.getId());
    }

    /** A listing with region NULL is sold everywhere, so a regional lookup must still find it. */
    @Test
    void listingWithNoStartDateAndNoRegion_isFoundByRegionalLookup() {
        ProductListing listing = seedPlainListing();

        assertThat(productListingRepository
                .findCurrentlyActiveByVariantIdAndRegion(listing.getVariant().getId(), "DE"))
                .get()
                .extracting(ProductListing::getId)
                .isEqualTo(listing.getId());
    }

    /**
     * The variant-scoped lookups and the product-scoped storefront gate must agree about what
     * "currently sellable" means — they are the same predicate, and disagreeing would let the PDP
     * open on a product whose variants cannot be priced.
     */
    @Test
    void variantLookupAgreesWithProductGateAndWithTheEntity() {
        ProductListing listing = seedPlainListing();

        assertThat(listing.isCurrentlyActive()).isTrue();
        assertThat(productListingRepository
                .existsCurrentlyActiveListingByProductId(listing.getProduct().getId())).isTrue();
        assertThat(productListingRepository
                .findCurrentlyActiveByVariantId(listing.getVariant().getId())).isPresent();
    }

    /** The NULL guard must not swallow a genuinely closed window. */
    @Test
    void listingWhoseWindowHasClosed_isNotSellable() {
        ProductListing listing = seedPlainListing();
        listing.setAvailableUntil(LocalDateTime.now().minusDays(1));
        productListingRepository.saveAndFlush(listing);

        assertThat(listing.isCurrentlyActive()).isFalse();
        assertThat(productListingRepository.findCurrentlyActiveByVariantId(listing.getVariant().getId()))
                .isEmpty();
        assertThat(productListingRepository
                .existsCurrentlyActiveListingByProductId(listing.getProduct().getId())).isFalse();
    }

    /** A start date still in the future must not be sellable yet. */
    @Test
    void listingWhoseWindowHasNotOpened_isNotSellable() {
        ProductListing listing = seedPlainListing();
        listing.setAvailableFrom(LocalDateTime.now().plusDays(1));
        productListingRepository.saveAndFlush(listing);

        assertThat(listing.isCurrentlyActive()).isFalse();
        assertThat(productListingRepository.findCurrentlyActiveByVariantId(listing.getVariant().getId()))
                .isEmpty();
    }
}
