package com.enunas.backend.discount;

import java.math.BigDecimal;

/**
 * The two structurally different discount kinds. The type is the absorption model and must
 * never be inferred elsewhere — it decides who pays for the discount:
 *
 *  - ADMIN: a marketplace marketing cost fully absorbed by Enunas. The brand payout is
 *           unchanged; only the platform's effective commission shrinks. Max 10%.
 *  - BRAND: a brand marketing cost split 50/50 between the brand and Enunas, and only ever
 *           applied to the issuing brand's own products. Max 15%.
 */
public enum DiscountType {

    ADMIN(new BigDecimal("0.1000")),
    BRAND(new BigDecimal("0.1500"));

    private final BigDecimal maxPercent;

    DiscountType(BigDecimal maxPercent) {
        this.maxPercent = maxPercent;
    }

    /** Hard upper bound on the discount fraction allowed for this type (e.g. 0.1000 = 10%). */
    public BigDecimal getMaxPercent() {
        return maxPercent;
    }
}
