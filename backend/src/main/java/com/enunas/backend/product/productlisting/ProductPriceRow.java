package com.enunas.backend.product.productlisting;

import java.math.BigDecimal;

/**
 * One product's lowest currently-sellable price, from the batched aggregate in
 * {@code ProductListingRepository.findLowestActivePricesByProductIds}. A product with no sellable
 * listing produces no row at all rather than a row with a null price — GROUP BY only emits groups
 * that matched — so callers read "absent" as "no price", never as a null they have to carry.
 */
public record ProductPriceRow(Long productId, BigDecimal price) {}
