package com.enunas.backend.product.productlisting;

import java.math.BigDecimal;

/**
 * One sellable listing's two prices, from
 * {@code ProductListingRepository.findSellableListingPricesByProductIds}. Deliberately a row per
 * LISTING rather than an aggregate per product: the storefront needs the list price and the sale
 * price of the <em>same</em> listing to render a strikethrough, and two independent MIN() aggregates
 * cannot promise that — on a product with several listings the cheapest sale price and the lowest
 * list price can come from different rows, which would pair a sale price with an unrelated
 * "original" and can even show an original below the price being charged.
 */
public record ListingPriceRow(Long productId, BigDecimal price, BigDecimal discountPrice) {

    /**
     * What a customer actually pays for this listing. Mirrors {@link ProductListing#getCurrentPrice()}
     * exactly, including its {@code > 0} test — a zero or negative discountPrice is not a sale.
     */
    public BigDecimal current() {
        return discountPrice != null && discountPrice.compareTo(BigDecimal.ZERO) > 0 ? discountPrice : price;
    }

    /** True when {@link #current()} is a reduction from {@link #price()}. */
    public boolean isDiscounted() {
        return current().compareTo(price) < 0;
    }
}
