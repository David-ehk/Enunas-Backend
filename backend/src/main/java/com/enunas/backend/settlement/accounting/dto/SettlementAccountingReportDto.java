package com.enunas.backend.settlement.accounting.dto;

import com.enunas.backend.settlement.accounting.ReconciliationStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class SettlementAccountingReportDto {

    // ===== Identity =====
    private final String settlementId;      // "SET-YYYY-MM"
    private final String period;            // "YYYY-MM"
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final String currency;
    private final LocalDate settlementDate; // report-generation date (today, Berlin)

    // ===== Ebene A: full settlement proof =====
    private final String payoutReference;               // admin-entered, nullable
    private final BigDecimal totalCustomerPayments;
    private final BigDecimal enunasCommissionNet;
    private final BigDecimal enunasVatRate;              // percentage, e.g. 19
    private final BigDecimal enunasVatAmount;
    private final BigDecimal enunasCommissionGross;
    private final boolean isCreditNote;
    private final BigDecimal brandProductAmount;
    private final BigDecimal brandShippingAmount;
    private final BigDecimal brandPayoutAmount;
    private final BigDecimal brandTotalAmount;
    private final BigDecimal mollieFees;                 // admin-entered, nullable
    private final Boolean mollieFeesIncludedInActualPayout; // admin-entered, nullable
    private final BigDecimal refundAmount;
    private final BigDecimal actualPayoutAmount;         // best-effort from PAID Payouts, nullable

    // ===== Reconciliation =====
    private final BigDecimal reconciliationDifference;   // totalCustomerPayments - (commissionGross + brandPayoutAmount)
    private final BigDecimal payoutDifference;            // actualPayoutAmount - brandPayoutAmount, nullable
    private final ReconciliationStatus reconciliationStatus;
    private final String reconciliationNote;

    // ===== Ebene B: EÜR booking lines =====
    private final List<AccountingBookingLineDto> bookingLines;

    // ===== Brand breakdown =====
    private final List<BrandAccountingBreakdownDto> brands;
}
