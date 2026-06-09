package com.enunas.backend.settlement.dto;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.ledger.LedgerRepository.PeriodAggregate;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One brand's settlement figures for a period. All amounts in EUR. {@code isCreditNote} is computed
 * on the fly (commissionNet &lt; 0 ⇒ net-negative month ⇒ operator issues a credit note, not an invoice).
 */
@Getter
@Builder
public class SettlementRowDto {

    private final Long brandId;
    private final String brandName;
    private final Boolean domestic;
    private final String vatId;
    private final String taxNumber;
    private final BigDecimal commissionNet;
    private final BigDecimal commissionVat;
    private final BigDecimal commissionGross;
    private final BigDecimal payoutAmount;
    private final long orderCount;
    private final long refundCount;

    @JsonProperty("isCreditNote")
    private final boolean creditNote;

    /** Builds a row from a live period aggregate joined with the brand's metadata. */
    public static SettlementRowDto from(PeriodAggregate a, BrandPartner brand) {
        BigDecimal commissionNet = nz(a.getCommissionNet());
        BigDecimal commissionVat = nz(a.getCommissionVat());
        return SettlementRowDto.builder()
                .brandId(a.getBrandId())
                .brandName(brand != null ? brand.getBrandName() : null)
                .domestic(brand != null ? brand.isDomestic() : null)
                .vatId(brand != null ? brand.getVatId() : null)
                .taxNumber(brand != null ? brand.getTaxNumber() : null)
                .commissionNet(commissionNet)
                .commissionVat(commissionVat)
                .commissionGross(commissionNet.add(commissionVat))
                .payoutAmount(nz(a.getPayoutAmount()))
                .orderCount(a.getOrderCount() != null ? a.getOrderCount() : 0L)
                .refundCount(a.getRefundCount() != null ? a.getRefundCount() : 0L)
                .creditNote(commissionNet.signum() < 0)
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
