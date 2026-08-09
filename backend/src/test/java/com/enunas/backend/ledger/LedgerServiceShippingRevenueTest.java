package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderShippingSnapshot;
import com.enunas.backend.shipping.ShippingCalculationMethod;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceShippingRevenueTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRepository, brandEconomicsRepository);
        ReflectionTestUtils.setField(ledgerService, "globalCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(ledgerService, "holdDays", 7);
    }

    private OrderShippingSnapshot snapshot(Long brandId, String amount) {
        return OrderShippingSnapshot.builder()
                .orderId(1L).brandPartnerId(brandId).amount(new BigDecimal(amount)).currency("EUR")
                .calculationMethod(ShippingCalculationMethod.BRAND_FLAT_RATE).ruleVersion("flat-v1")
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordShippingRevenue_creditsBrandAndNeverTouchesCommission() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);

        BrandEconomics eco = BrandEconomics.builder().build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));
        when(ledgerRepository.existsByOrderIdAndEntryType(1L, LedgerEntryType.SHIPPING_REVENUE)).thenReturn(false);

        ledgerService.recordShippingRevenue(order, List.of(snapshot(5L, "4.99")));

        assertThat(eco.getPendingBalance()).isEqualByComparingTo("4.99");
        assertThat(eco.getLifetimeRevenue()).isEqualByComparingTo("4.99");

        ArgumentCaptor<List<LedgerEntry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepository).saveAll(cap.capture());
        LedgerEntry entry = cap.getValue().get(0);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.SHIPPING_REVENUE);
        assertThat(entry.getPlatformFee()).isEqualByComparingTo("0");
        assertThat(entry.getCommissionNet()).isEqualByComparingTo("0");
        assertThat(entry.getBrandPayout()).isEqualByComparingTo("4.99");
    }

    @Test
    void recordShippingRevenue_idempotent_skipsWhenAlreadyRecorded() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(ledgerRepository.existsByOrderIdAndEntryType(1L, LedgerEntryType.SHIPPING_REVENUE)).thenReturn(true);

        ledgerService.recordShippingRevenue(order, List.of(snapshot(5L, "4.99")));

        verify(ledgerRepository, never()).saveAll(any());
        verifyNoInteractions(brandEconomicsRepository);
    }

    @Test
    void recordShippingRevenue_emptyList_noOp() {
        Order order = mock(Order.class);

        ledgerService.recordShippingRevenue(order, List.of());

        verifyNoInteractions(ledgerRepository, brandEconomicsRepository);
    }
}
