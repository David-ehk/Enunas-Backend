package com.enunas.backend.settlement.accounting.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One brand's slice of the platform-level settlement (spec §7's optional brands[] breakdown).
 *  All figures net-to-brand (LedgerEntry.brandPayout), consistent with the platform totals. */
@Getter
@Builder
public class BrandAccountingBreakdownDto {
    private final Long brandId;
    private final String brandName;
    private final BigDecimal productAmount;
    private final BigDecimal shippingAmount;
    private final BigDecimal commissionNet;
    private final BigDecimal commissionVat;
    private final BigDecimal commissionGross;
    private final BigDecimal refundAmount;
    private final BigDecimal payoutAmount;
}
