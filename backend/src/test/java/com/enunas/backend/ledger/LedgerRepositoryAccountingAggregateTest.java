package com.enunas.backend.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class LedgerRepositoryAccountingAggregateTest {

    @Autowired private LedgerRepository ledgerRepository;

    private LedgerEntry entry(LedgerEntryType type, BigDecimal total, BigDecimal commissionNet,
                              BigDecimal commissionVat, BigDecimal brandPayout, String externalRef) {
        return LedgerEntry.builder()
                .brandPartnerId(1L)
                .totalAmount(total)
                .platformFee(commissionNet)
                .brandPayout(brandPayout)
                .commissionNet(commissionNet)
                .commissionVat(commissionVat)
                .commissionRate(new BigDecimal("0.1800"))
                .currency("EUR")
                .entryType(type)
                .status(LedgerEntryStatus.PENDING_RELEASE)
                .payoutEligibleAt(LocalDateTime.now())
                .externalReferenceId(externalRef)
                .build();
    }

    @Test
    void splitsProductAndShippingNetOfTheirOwnRefunds() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        // Product sale: 119 gross, 18.00 net commission, 3.42 VAT, 97.58 to brand.
        ledgerRepository.save(entry(LedgerEntryType.ORDER_PAYMENT,
                new BigDecimal("119.00"), new BigDecimal("18.00"), new BigDecimal("3.42"),
                new BigDecimal("97.58"), null));
        // Shipping: 10.00, zero commission.
        ledgerRepository.save(entry(LedgerEntryType.SHIPPING_REVENUE,
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.00"), null));
        // Product refund: half the product entry reversed.
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-59.50"), new BigDecimal("-9.00"), new BigDecimal("-1.71"),
                new BigDecimal("-48.79"), "re_product1"));
        // Shipping refund: the whole shipping entry reversed (tagged :SHIPPING).
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("-10.00"), "re_product1:SHIPPING"));

        List<LedgerRepository.AccountingPeriodAggregate> rows =
                ledgerRepository.aggregateAccountingByBrandForPeriod(start, end);

        assertThat(rows).hasSize(1);
        LedgerRepository.AccountingPeriodAggregate row = rows.get(0);
        assertThat(row.getBrandId()).isEqualTo(1L);
        assertThat(row.getCommissionNet()).isEqualByComparingTo("9.00");     // 18.00 - 9.00
        assertThat(row.getCommissionVat()).isEqualByComparingTo("1.71");    // 3.42 - 1.71
        assertThat(row.getProductRevenueNet()).isEqualByComparingTo("48.79"); // 97.58 - 48.79
        assertThat(row.getShippingRevenueNet()).isEqualByComparingTo("0.00"); // 10.00 - 10.00, fully refunded
        assertThat(row.getRefundAmount()).isEqualByComparingTo("69.50");    // 59.50 + 10.00, reported positive
        assertThat(row.getOrderCount()).isEqualTo(1L);
        assertThat(row.getRefundCount()).isEqualTo(2L);
    }
}
