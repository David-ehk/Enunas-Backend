package com.enunas.backend.discount;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of applying a discount code to a cart. {@code itemShares} is aligned by index to the
 * OrderItem list passed in, so the caller can fold each item's NET platform/brand discount share
 * into its money snapshot. All share/aggregate figures are NET amounts. The aggregate fields are
 * the order-level snapshot.
 *
 * Invariant: {@code platformDiscountAmount + brandDiscountAmount == discountAmount}.
 */
public record DiscountApplication(
        DiscountCode code,
        DiscountType type,
        BigDecimal percent,
        List<ItemShare> itemShares,
        BigDecimal discountAmount,
        BigDecimal platformDiscountAmount,
        BigDecimal brandDiscountAmount) {

    /** Per-item NET split of the discount: how much Enunas absorbs vs how much the brand absorbs. */
    public record ItemShare(BigDecimal platformShareNet, BigDecimal brandShareNet) {
        public BigDecimal total() {
            return platformShareNet.add(brandShareNet);
        }
    }
}
