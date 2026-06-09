package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the two production-critical ledger properties introduced with discounts:
 *  1. recordOrderPayment credits the brand from the immutable OrderItem snapshot (so a
 *     discounted payout is honoured), and a full refund returns the balance to baseline.
 *  2. Legacy orders without a snapshot fall back to recomputing lineTotal × rate.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceSnapshotTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRepository, brandEconomicsRepository);
        ReflectionTestUtils.setField(ledgerService, "globalCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(ledgerService, "holdDays", 7);
    }

    private OrderItem item(BigDecimal lineTotal, BigDecimal rate,
                           BigDecimal platformFee, BigDecimal brandPayout) {
        OrderItem it = mock(OrderItem.class);
        lenient().when(it.getId()).thenReturn(10L);
        lenient().when(it.getBrandId()).thenReturn(5L);
        lenient().when(it.getLineTotal()).thenReturn(lineTotal);
        lenient().when(it.getCommissionRate()).thenReturn(rate);
        lenient().when(it.getPlatformFeeAmount()).thenReturn(platformFee);
        lenient().when(it.getBrandPayoutAmount()).thenReturn(brandPayout);
        return it;
    }

    /** Post-V5 item carrying the explicit net/VAT snapshot (§7 BRAND 15% case, gross 119). */
    private OrderItem netItem() {
        OrderItem it = mock(OrderItem.class);
        lenient().when(it.getId()).thenReturn(10L);
        lenient().when(it.getBrandId()).thenReturn(5L);
        lenient().when(it.getCommissionRate()).thenReturn(new BigDecimal("0.18"));
        lenient().when(it.getLineTotal()).thenReturn(new BigDecimal("119.00"));
        lenient().when(it.getCommissionNet()).thenReturn(new BigDecimal("10.50"));
        lenient().when(it.getCommissionVat()).thenReturn(new BigDecimal("2.00"));
        lenient().when(it.getBrandNetRevenue()).thenReturn(new BigDecimal("74.50"));
        lenient().when(it.getBrandPayout()).thenReturn(new BigDecimal("88.65"));
        lenient().when(it.getBrandPayoutAmount()).thenReturn(new BigDecimal("88.65"));
        lenient().when(it.getCustomerGrossAfterDiscount()).thenReturn(new BigDecimal("101.15"));
        return it;
    }

    private Order order(OrderItem item, String total) {
        Order o = mock(Order.class);
        lenient().when(o.getId()).thenReturn(1L);
        lenient().when(o.getCurrency()).thenReturn("EUR");
        lenient().when(o.getTotal()).thenReturn(new BigDecimal(total));
        lenient().when(o.getItems()).thenReturn(List.of(item));
        return o;
    }

    @Test
    @SuppressWarnings("unchecked")
    void brandDiscountedOrder_creditsSnapshotPayout_andRefundReturnsToBaseline() {
        BrandEconomics eco = BrandEconomics.builder().build(); // all balances ZERO baseline
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));

        // BRAND 15% on a €119 gross item: snapshot brandPayout 88.65, commissionNet 10.50,
        // commissionVat 2.00, customer paid 101.15.
        OrderItem it = netItem();
        Order ord = order(it, "101.15");

        when(ledgerRepository.existsByOrderIdAndEntryType(1L, LedgerEntryType.ORDER_PAYMENT)).thenReturn(false);

        ledgerService.recordOrderPayment(ord);

        // Brand credited the discounted cash payout from the snapshot.
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("88.65");
        assertThat(eco.getLifetimeRevenue()).isEqualByComparingTo("88.65");

        ArgumentCaptor<List<LedgerEntry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepository).saveAll(cap.capture());
        LedgerEntry entry = cap.getValue().get(0);
        assertThat(entry.getPlatformFee()).isEqualByComparingTo("10.50");    // = commissionNet
        assertThat(entry.getCommissionNet()).isEqualByComparingTo("10.50");
        assertThat(entry.getCommissionVat()).isEqualByComparingTo("2.00");
        assertThat(entry.getBrandPayout()).isEqualByComparingTo("88.65");

        // ---- Full refund of the discounted total ----
        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("ref1", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 5L)).thenReturn(List.of());

        ledgerService.recordRefund(ord, new BigDecimal("101.15"), "ref1");

        // Balance returns to EXACTLY the pre-order baseline (no penny drift).
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("0.00");
        assertThat(eco.getOutstandingDebt()).isEqualByComparingTo("0.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyOrderWithNullSnapshot_fallsBackToRecompute() {
        BrandEconomics eco = BrandEconomics.builder()
                .defaultCommissionRate(new BigDecimal("0.18")).build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));

        // Old order: no snapshot fields, no commission rate stored.
        OrderItem it = item(new BigDecimal("100.00"), null, null, null);
        Order ord = order(it, "100.00");

        when(ledgerRepository.existsByOrderIdAndEntryType(1L, LedgerEntryType.ORDER_PAYMENT)).thenReturn(false);

        ledgerService.recordOrderPayment(ord);

        // Recomputed: fee 18.00, payout 82.00.
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("82.00");

        ArgumentCaptor<List<LedgerEntry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepository).saveAll(cap.capture());
        LedgerEntry entry = cap.getValue().get(0);
        assertThat(entry.getPlatformFee()).isEqualByComparingTo("18.00");
        assertThat(entry.getBrandPayout()).isEqualByComparingTo("82.00");
    }
}
