package com.enunas.backend.settlement.accounting;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.PeriodNotClosedException;
import com.enunas.backend.ledger.LedgerRepository;
import com.enunas.backend.payout.PayoutRepository;
import com.enunas.backend.settlement.accounting.dto.SettlementAccountingReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementAccountingReportServiceTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private PayoutRepository payoutRepository;
    @Mock private BrandPartnerRepository brandPartnerRepository;
    @Mock private SettlementAccountingInputRepository accountingInputRepository;

    private SettlementAccountingReportService service;

    @BeforeEach
    void setUp() {
        service = new SettlementAccountingReportService(
                ledgerRepository, payoutRepository, brandPartnerRepository, accountingInputRepository);
        ReflectionTestUtils.setField(service, "vatServiceRate", new BigDecimal("0.19"));
    }

    private LedgerRepository.AccountingPeriodAggregate agg(Long brandId, String commissionNet, String commissionVat,
                                                            String productNet, String shippingNet, String refund,
                                                            String total, long orderCount, long refundCount) {
        LedgerRepository.AccountingPeriodAggregate a = mock(LedgerRepository.AccountingPeriodAggregate.class);
        lenient().when(a.getBrandId()).thenReturn(brandId);
        lenient().when(a.getCommissionNet()).thenReturn(new BigDecimal(commissionNet));
        lenient().when(a.getCommissionVat()).thenReturn(new BigDecimal(commissionVat));
        lenient().when(a.getProductRevenueNet()).thenReturn(new BigDecimal(productNet));
        lenient().when(a.getShippingRevenueNet()).thenReturn(new BigDecimal(shippingNet));
        lenient().when(a.getRefundAmount()).thenReturn(new BigDecimal(refund));
        lenient().when(a.getTotalAmount()).thenReturn(new BigDecimal(total));
        lenient().when(a.getOrderCount()).thenReturn(orderCount);
        lenient().when(a.getRefundCount()).thenReturn(refundCount);
        return a;
    }

    @Test
    void closedPeriodGuard_rejectsCurrentMonth() {
        YearMonth current = YearMonth.now(ZoneId.of("Europe/Berlin"));
        assertThatThrownBy(() -> service.generateReport("SET-" + current))
                .isInstanceOf(PeriodNotClosedException.class);
    }

    @Test
    void malformedSettlementId_rejected() {
        assertThatThrownBy(() -> service.generateReport("2026-08"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noActualPayout_forcesUnreconciled_evenWhenLedgerInvariantHolds() {
        var aggResult = agg(1L, "18.00", "3.42", "97.58", "10.00", "0.00", "129.00", 1, 0);
        lenient().when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(aggResult));
        lenient().when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        lenient().when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(0L);
        lenient().when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getEnunasCommissionGross()).isEqualByComparingTo("21.42");
        assertThat(report.getBrandPayoutAmount()).isEqualByComparingTo("107.58");
        assertThat(report.getTotalCustomerPayments()).isEqualByComparingTo("129.00");
        assertThat(report.getReconciliationDifference()).isEqualByComparingTo("0.00"); // ledger-internal: balanced
        assertThat(report.getActualPayoutAmount()).isNull();                          // no PAID payout found
        assertThat(report.getPayoutDifference()).isNull();
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.UNRECONCILED);
        assertThat(report.getBookingLines()).hasSize(1); // INCOME_COMMISSION only, no mollieFees entered
        assertThat(report.getBookingLines().get(0).getBookingType()).isEqualTo(BookingType.INCOME_COMMISSION);
        assertThat(report.getBookingLines().get(0).getAmount()).isEqualByComparingTo("21.42");
        assertThat(report.getBookingLines().get(0).getVatRate()).isEqualByComparingTo("19");
    }

    @Test
    void matchingActualPayout_andNoDrift_reconciles() {
        var aggResult = agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.00", 1, 0);
        lenient().when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(aggResult));
        lenient().when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        lenient().when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(new BigDecimal("97.58"));
        lenient().when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(1L);
        lenient().when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getActualPayoutAmount()).isEqualByComparingTo("97.58");
        assertThat(report.getPayoutDifference()).isEqualByComparingTo("0.00");
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RECONCILED);
    }

    @Test
    void mismatchedActualPayout_forcesUnreconciled() {
        var aggResult = agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.00", 1, 0);
        lenient().when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(aggResult));
        lenient().when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        lenient().when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(1L); // paid, but zero — e.g. fully clawed back
        lenient().when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getPayoutDifference()).isEqualByComparingTo("-97.58");
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.UNRECONCILED);
    }

    @Test
    void mollieFeesEntered_addsExpenseLine_withoutTouchingCommission() {
        var aggResult = agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.00", 1, 0);
        lenient().when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(aggResult));
        lenient().when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        lenient().when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(0L);
        lenient().when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.of(
                SettlementAccountingInput.builder()
                        .period("2026-05").mollieFees(new BigDecimal("3.57"))
                        .mollieFeesIncludedInActualPayout(false).payoutReference("stl_test123")
                        .enteredByAdminEmail("admin@it.local")
                        .enteredAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                        .build()));

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getMollieFees()).isEqualByComparingTo("3.57");
        assertThat(report.getMollieFeesIncludedInActualPayout()).isFalse();
        assertThat(report.getPayoutReference()).isEqualTo("stl_test123");
        assertThat(report.getEnunasCommissionGross()).isEqualByComparingTo("21.42"); // unaffected by fee
        assertThat(report.getBookingLines()).hasSize(2);
        assertThat(report.getBookingLines().stream().anyMatch(l ->
                l.getBookingType() == BookingType.EXPENSE_MOLLIE_FEE
                        && l.getAmount().compareTo(new BigDecimal("3.57")) == 0
                        && l.getVatRate().compareTo(BigDecimal.ZERO) == 0)).isTrue();
    }

    @Test
    void ledgerInternalInvariantViolation_forcesUnreconciled() {
        // Total amount doesn't match commissionGross + payoutAmount: 119.01 != (21.42 + 97.58)
        var aggResult = agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.01", 1, 0);
        lenient().when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(aggResult));
        lenient().when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        lenient().when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(new BigDecimal("97.58"));
        lenient().when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(1L);
        lenient().when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getReconciliationDifference()).isEqualByComparingTo("0.01");
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.UNRECONCILED);
        assertThat(report.getReconciliationNote()).contains("Ledger-internal invariant violated");
    }
}
