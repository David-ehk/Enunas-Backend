package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.RefundQuote;
import com.enunas.backend.order.RefundQuote.LineInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceReturnReversalTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRepository, brandEconomicsRepository);
        ReflectionTestUtils.setField(ledgerService, "globalCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(ledgerService, "holdDays", 7);
    }

    private Order order() {
        Order o = mock(Order.class);
        lenient().when(o.getId()).thenReturn(1L);
        lenient().when(o.getCurrency()).thenReturn("EUR");
        return o;
    }

    private RefundQuote quote(String shipping) {
        return RefundQuote.of(List.of(
                new LineInput(10L, 1, new BigDecimal("100.00"), new BigDecimal("15.00"), new BigDecimal("2.85"),
                        new BigDecimal("82.15"), 0, 1),
                new LineInput(11L, 1, new BigDecimal("27.00"), new BigDecimal("4.05"), new BigDecimal("0.77"),
                        new BigDecimal("22.18"), 0, 1)), new BigDecimal(shipping));
    }

    private LedgerEntry shippingEntry(BigDecimal amount) {
        return LedgerEntry.builder()
                .id(99L).orderId(1L).brandPartnerId(7L)
                .totalAmount(amount).platformFee(BigDecimal.ZERO).brandPayout(amount)
                .commissionNet(BigDecimal.ZERO).commissionVat(BigDecimal.ZERO).commissionRate(BigDecimal.ZERO)
                .currency("EUR").entryType(LedgerEntryType.SHIPPING_REVENUE).status(LedgerEntryStatus.PENDING_RELEASE)
                .payoutEligibleAt(LocalDateTime.now()).build();
    }

    @Test
    void booksExactlyTheQuote_productAndShipping() {
        BrandEconomics eco = BrandEconomics.builder().pendingBalance(new BigDecimal("200.00")).build();
        when(brandEconomicsRepository.findByBrandPartner_Id(7L)).thenReturn(Optional.of(eco));
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 7L)).thenReturn(List.of());
        when(ledgerRepository.findActiveShippingEntriesByOrderAndBrand(1L, 7L))
                .thenReturn(List.of(shippingEntry(new BigDecimal("4.99"))));

        ledgerService.recordReturnReversal(order(), 7L, quote("4.99"), "re_1");

        ArgumentCaptor<LedgerEntry> cap = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerRepository, times(2)).save(cap.capture());
        LedgerEntry product = cap.getAllValues().get(0), shipping = cap.getAllValues().get(1);
        assertThat(product.getCommissionNet()).isEqualByComparingTo("-19.05");
        assertThat(product.getCommissionVat()).isEqualByComparingTo("-3.62");
        assertThat(product.getBrandPayout()).isEqualByComparingTo("-104.33");
        assertThat(product.getTotalAmount()).isEqualByComparingTo("-127.00");
        assertThat(product.getEntryType()).isEqualTo(LedgerEntryType.REFUND_REVERSAL);
        assertThat(product.getExternalReferenceId()).isEqualTo("re_1");
        assertThat(shipping.getBrandPayout()).isEqualByComparingTo("-4.99");
        assertThat(shipping.getExternalReferenceId()).isEqualTo("re_1:SHIPPING");
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("90.68"); // 200 - 104.33 - 4.99
    }

    @Test
    void noShippingEntry_whenQuoteShippingIsZero() {
        BrandEconomics eco = BrandEconomics.builder().pendingBalance(new BigDecimal("200.00")).build();
        when(brandEconomicsRepository.findByBrandPartner_Id(7L)).thenReturn(Optional.of(eco));
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 7L)).thenReturn(List.of());

        ledgerService.recordReturnReversal(order(), 7L, quote("0"), "re_1");

        verify(ledgerRepository, times(1)).save(any(LedgerEntry.class));
        verify(ledgerRepository, never()).findActiveShippingEntriesByOrderAndBrand(anyLong(), anyLong());
    }

    @Test
    void duplicateRefundId_savesNothing() {
        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("re_1", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(true);

        ledgerService.recordReturnReversal(order(), 7L, quote("4.99"), "re_1");

        verify(ledgerRepository, never()).save(any(LedgerEntry.class));
    }
}
