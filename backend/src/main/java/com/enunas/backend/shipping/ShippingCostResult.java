package com.enunas.backend.shipping;

import java.math.BigDecimal;

/**
 * @param brandShippingProfileId which {@code BrandShippingProfile} row produced this amount, if
 *                                any — debugging traceability only, never read back into a
 *                                calculation.
 */
public record ShippingCostResult(
        BigDecimal amount,
        String currency,
        ShippingCalculationMethod method,
        String ruleVersion,
        Long brandShippingProfileId
) {}
