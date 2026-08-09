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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceRefundSplitTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRepository, brandEconomicsRepository);
        ReflectionTestUtils.setField(ledgerService, "globalCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(ledgerService, "holdDays", 7);
    }

    private OrderItem productItem() {
        OrderItem it = mock(OrderItem.class);
        lenient().when(it.getId()).thenReturn(10L);
        lenient().when(it.getBrandId()).thenReturn(5L);
        lenient().when(it.getCommissionRate()).thenReturn(new BigDecimal("0.18"));
        lenient().when(it.getLineTotal()).thenReturn(new BigDecimal("100.00"));
        lenient().when(it.getLineGross()).thenReturn(new BigDecimal("100.00"));
        lenient().when(it.getCommissionNet()).thenReturn(new BigDecimal("18.00"));
        lenient().when(it.getCommissionVat()).thenReturn(BigDecimal.ZERO);
        lenient().when(it.getBrandPayoutAmount()).thenReturn(new BigDecimal("82.00"));
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

    private LedgerEntry shippingEntry(BigDecimal amount) {
        return LedgerEntry.builder()
                .id(99L).orderId(1L).brandPartnerId(5L)
                .totalAmount(amount).platformFee(BigDecimal.ZERO).brandPayout(amount)
                .commissionNet(BigDecimal.ZERO).commissionVat(BigDecimal.ZERO).commissionRate(BigDecimal.ZERO)
                .currency("EUR").entryType(LedgerEntryType.SHIPPING_REVENUE).status(LedgerEntryStatus.PENDING_RELEASE)
                .payoutEligibleAt(LocalDateTime.now()).build();
    }

    @Test
    void orderWideCancel_reversesBothProductAndShippingEntries() {
        BrandEconomics eco = BrandEconomics.builder()
                .pendingBalance(new BigDecimal("86.99")) // 82.00 product + 4.99 shipping
                .build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));

        Order ord = order(productItem(), "104.99"); // 100 product + 4.99 shipping

        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("full-cancel", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("full-cancel:SHIPPING", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 5L)).thenReturn(List.of());
        when(ledgerRepository.findActiveShippingEntriesByOrderAndBrand(1L, 5L))
                .thenReturn(List.of(shippingEntry(new BigDecimal("4.99"))));

        ledgerService.recordRefund(ord, new BigDecimal("104.99"), "full-cancel");

        // Both product (82.00) and shipping (4.99) fully reversed -> balance back to zero.
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("0.00");

        ArgumentCaptor<LedgerEntry> cap = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerRepository, times(2)).save(cap.capture());
        List<LedgerEntry> saved = cap.getAllValues();
        assertThat(saved).extracting(LedgerEntry::getEntryType)
                .containsExactly(LedgerEntryType.REFUND_REVERSAL, LedgerEntryType.REFUND_REVERSAL);
        assertThat(saved.get(1).getExternalReferenceId()).isEqualTo("full-cancel:SHIPPING");
        assertThat(saved.get(1).getBrandPayout()).isEqualByComparingTo("-4.99");
    }

    @Test
    void brandScopedReturn_reversesOnlyProductEntries_shippingUntouched() {
        BrandEconomics eco = BrandEconomics.builder().pendingBalance(new BigDecimal("86.99")).build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));

        Order ord = order(productItem(), "104.99");

        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("return-1", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 5L)).thenReturn(List.of());

        ledgerService.recordRefund(ord, 5L, new BigDecimal("100.00"), "return-1");

        // Only the 82.00 product payout reversed; shipping's 4.99 stays untouched.
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("4.99");

        verify(ledgerRepository, times(1)).save(any(LedgerEntry.class));
        verify(ledgerRepository, never()).findActiveShippingEntriesByOrderAndBrand(anyLong(), anyLong());
    }
}
