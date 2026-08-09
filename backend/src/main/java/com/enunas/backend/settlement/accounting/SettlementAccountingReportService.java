package com.enunas.backend.settlement.accounting;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.PeriodNotClosedException;
import com.enunas.backend.ledger.LedgerRepository;
import com.enunas.backend.payout.PayoutRepository;
import com.enunas.backend.settlement.accounting.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Platform-level, period-scoped accounting report. Every figure is a live SUM over
 * {@link com.enunas.backend.ledger.LedgerEntry}/{@link com.enunas.backend.payout.Payout} — no
 * second money-calculation path, so this can never drift from the ledger (see
 * SettlementAccountingReportServiceTest for the reconciliation-status contract). The only
 * persisted state this service touches is {@link SettlementAccountingInput} — the two facts
 * (Mollie fees, confirmed payout reference) the system has no other source for.
 */
@Service
@RequiredArgsConstructor
public class SettlementAccountingReportService {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SETTLEMENT_ID_PREFIX = "SET-";

    private final LedgerRepository ledgerRepository;
    private final PayoutRepository payoutRepository;
    private final BrandPartnerRepository brandPartnerRepository;
    private final SettlementAccountingInputRepository accountingInputRepository;

    @Value("${enunas.vat.service-rate:0.19}")
    private BigDecimal vatServiceRate;

    @Transactional(readOnly = true)
    public SettlementAccountingReportDto generateReport(String settlementId) {
        YearMonth ym = parseSettlementId(settlementId);
        String period = ym.toString();

        // Same closed-period rule as SettlementService.settle(): only fully-closed months.
        LocalDate nextPeriodStart = ym.plusMonths(1).atDay(1);
        if (LocalDate.now(BERLIN).isBefore(nextPeriodStart)) {
            throw new PeriodNotClosedException("Nur abgeschlossene Monate können abgerechnet werden.");
        }

        LocalDateTime startUtc = ym.atDay(1).atStartOfDay(BERLIN).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = ym.plusMonths(1).atDay(1).atStartOfDay(BERLIN).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        List<LedgerRepository.AccountingPeriodAggregate> rows =
                ledgerRepository.aggregateAccountingByBrandForPeriod(startUtc, endUtc);

        List<BrandAccountingBreakdownDto> brands = new ArrayList<>();
        BigDecimal totalCustomerPayments = BigDecimal.ZERO;
        BigDecimal commissionNet = BigDecimal.ZERO;
        BigDecimal commissionVat = BigDecimal.ZERO;
        BigDecimal productAmount = BigDecimal.ZERO;
        BigDecimal shippingAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = BigDecimal.ZERO;

        for (LedgerRepository.AccountingPeriodAggregate row : rows) {
            BigDecimal rowCommissionGross = row.getCommissionNet().add(row.getCommissionVat());
            BigDecimal rowPayout = row.getProductRevenueNet().add(row.getShippingRevenueNet());
            BrandPartner brand = brandPartnerRepository.findById(row.getBrandId()).orElse(null);

            brands.add(BrandAccountingBreakdownDto.builder()
                    .brandId(row.getBrandId())
                    .brandName(brand != null ? brand.getBrandName() : null)
                    .productAmount(row.getProductRevenueNet())
                    .shippingAmount(row.getShippingRevenueNet())
                    .commissionNet(row.getCommissionNet())
                    .commissionVat(row.getCommissionVat())
                    .commissionGross(rowCommissionGross)
                    .refundAmount(row.getRefundAmount())
                    .payoutAmount(rowPayout)
                    .build());

            totalCustomerPayments = totalCustomerPayments.add(row.getTotalAmount());
            commissionNet = commissionNet.add(row.getCommissionNet());
            commissionVat = commissionVat.add(row.getCommissionVat());
            productAmount = productAmount.add(row.getProductRevenueNet());
            shippingAmount = shippingAmount.add(row.getShippingRevenueNet());
            refundAmount = refundAmount.add(row.getRefundAmount());
        }

        BigDecimal commissionGross = commissionNet.add(commissionVat);
        BigDecimal payoutAmount = productAmount.add(shippingAmount);

        BigDecimal actualPayoutAmount = null;
        long paidCount = payoutRepository.countPaidPayoutsInRange(startUtc, endUtc);
        if (paidCount > 0) {
            actualPayoutAmount = payoutRepository.sumPaidAmountInRange(startUtc, endUtc);
        }

        BigDecimal reconciliationDifference = totalCustomerPayments.subtract(commissionGross).subtract(payoutAmount);
        BigDecimal payoutDifference = actualPayoutAmount != null ? actualPayoutAmount.subtract(payoutAmount) : null;

        ReconciliationStatus status;
        String note;
        if (reconciliationDifference.compareTo(BigDecimal.ZERO) != 0) {
            status = ReconciliationStatus.UNRECONCILED;
            note = "Ledger-internal invariant violated: totalCustomerPayments != commissionGross + brandPayoutAmount.";
        } else if (actualPayoutAmount == null) {
            status = ReconciliationStatus.UNRECONCILED;
            note = "No confirmed PAID payout found in this period yet.";
        } else if (payoutDifference.compareTo(BigDecimal.ZERO) != 0) {
            status = ReconciliationStatus.UNRECONCILED;
            note = "Actual payout does not match computed brand payout for this period.";
        } else {
            status = ReconciliationStatus.RECONCILED;
            note = null;
        }

        Optional<SettlementAccountingInput> input = accountingInputRepository.findByPeriod(period);
        BigDecimal mollieFees = input.map(SettlementAccountingInput::getMollieFees).orElse(null);
        Boolean mollieFeesIncluded = input.map(SettlementAccountingInput::getMollieFeesIncludedInActualPayout).orElse(null);
        String payoutReference = input.map(SettlementAccountingInput::getPayoutReference).orElse(null);

        List<AccountingBookingLineDto> bookingLines = new ArrayList<>();
        LocalDate today = LocalDate.now(BERLIN);
        BigDecimal vatRatePercent = vatServiceRate.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
        bookingLines.add(AccountingBookingLineDto.builder()
                .settlementId(settlementId).bookingDate(today).bookingType(BookingType.INCOME_COMMISSION)
                .amount(commissionGross).currency("EUR").vatRate(vatRatePercent)
                .description("Enunas-Provision Settlement " + period).externalReference(settlementId)
                .build());
        if (mollieFees != null) {
            bookingLines.add(AccountingBookingLineDto.builder()
                    .settlementId(settlementId).bookingDate(today).bookingType(BookingType.EXPENSE_MOLLIE_FEE)
                    .amount(mollieFees).currency("EUR").vatRate(BigDecimal.ZERO)
                    .description("Mollie-Gebühren Settlement " + period).externalReference(settlementId)
                    .build());
        }

        return SettlementAccountingReportDto.builder()
                .settlementId(settlementId).period(period)
                .periodStart(ym.atDay(1)).periodEnd(ym.atEndOfMonth())
                .currency("EUR").settlementDate(today)
                .payoutReference(payoutReference)
                .totalCustomerPayments(totalCustomerPayments)
                .enunasCommissionNet(commissionNet).enunasVatRate(vatRatePercent).enunasVatAmount(commissionVat)
                .enunasCommissionGross(commissionGross).isCreditNote(commissionNet.signum() < 0)
                .brandProductAmount(productAmount).brandShippingAmount(shippingAmount)
                .brandPayoutAmount(payoutAmount).brandTotalAmount(totalCustomerPayments)
                .mollieFees(mollieFees).mollieFeesIncludedInActualPayout(mollieFeesIncluded)
                .refundAmount(refundAmount).actualPayoutAmount(actualPayoutAmount)
                .reconciliationDifference(reconciliationDifference).payoutDifference(payoutDifference)
                .reconciliationStatus(status).reconciliationNote(note)
                .bookingLines(bookingLines).brands(brands)
                .build();
    }

    @Transactional
    public void upsertInput(String period, UpsertAccountingInputDto dto, String adminEmail) {
        parsePeriod(period); // validates format, throws IllegalArgumentException otherwise
        SettlementAccountingInput input = accountingInputRepository.findByPeriod(period)
                .orElseGet(() -> SettlementAccountingInput.builder()
                        .period(period).enteredByAdminEmail(adminEmail).enteredAt(LocalDateTime.now())
                        .build());
        if (dto.getMollieFees() != null) input.setMollieFees(dto.getMollieFees());
        if (dto.getMollieFeesIncludedInActualPayout() != null) input.setMollieFeesIncludedInActualPayout(dto.getMollieFeesIncludedInActualPayout());
        if (dto.getPayoutReference() != null) input.setPayoutReference(dto.getPayoutReference());
        if (dto.getMollieSettlementDate() != null) input.setMollieSettlementDate(dto.getMollieSettlementDate());
        if (dto.getNotes() != null) input.setNotes(dto.getNotes());
        input.setUpdatedAt(LocalDateTime.now());
        accountingInputRepository.save(input);
    }

    private YearMonth parseSettlementId(String settlementId) {
        if (settlementId == null || !settlementId.startsWith(SETTLEMENT_ID_PREFIX)) {
            throw new IllegalArgumentException(
                    "Invalid settlementId, expected 'SET-YYYY-MM': " + settlementId);
        }
        return parsePeriod(settlementId.substring(SETTLEMENT_ID_PREFIX.length()));
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid period format, expected YYYY-MM: " + period);
        }
    }
}
