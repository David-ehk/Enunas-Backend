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
        // Product refund: half the product entry reversed (with non-null external ref).
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-59.50"), new BigDecimal("-9.00"), new BigDecimal("-1.71"),
                new BigDecimal("-48.79"), "re_product1"));
        // Shipping refund: the whole shipping entry reversed (tagged :SHIPPING).
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("-10.00"), "re_product1:SHIPPING"));
        // Second product sale: 50 gross, 9.00 net commission, 1.71 VAT, 39.29 to brand.
        ledgerRepository.save(entry(LedgerEntryType.ORDER_PAYMENT,
                new BigDecimal("50.00"), new BigDecimal("9.00"), new BigDecimal("1.71"),
                new BigDecimal("39.29"), null));
        // Product refund with null external ref: quarter of the second product entry reversed.
        // This tests the IS NULL condition in the query (not just NOT LIKE).
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-12.50"), new BigDecimal("-2.25"), new BigDecimal("-0.43"),
                new BigDecimal("-9.82"), null));

        List<LedgerRepository.AccountingPeriodAggregate> rows =
                ledgerRepository.aggregateAccountingByBrandForPeriod(start, end);

        assertThat(rows).hasSize(1);
        LedgerRepository.AccountingPeriodAggregate row = rows.get(0);
        assertThat(row.getBrandId()).isEqualTo(1L);
        // Commission: (18.00 + 9.00) - (9.00 + 2.25) = 27.00 - 11.25 = 15.75
        assertThat(row.getCommissionNet()).isEqualByComparingTo("15.75");
        // VAT: (3.42 + 1.71) - (1.71 + 0.43) = 5.13 - 2.14 = 2.99
        assertThat(row.getCommissionVat()).isEqualByComparingTo("2.99");
        // Product revenue: (97.58 + 39.29) - (48.79 + 9.82) = 136.87 - 58.61 = 78.26
        assertThat(row.getProductRevenueNet()).isEqualByComparingTo("78.26");
        // Shipping revenue: 10.00 - 10.00 = 0.00 (fully refunded)
        assertThat(row.getShippingRevenueNet()).isEqualByComparingTo("0.00");
        // Refunds: 59.50 + 10.00 + 12.50 = 82.00 (reported positive)
        assertThat(row.getRefundAmount()).isEqualByComparingTo("82.00");
        // Total: all entries sum to 119.00 + 10.00 + 50.00 - 59.50 - 10.00 - 12.50 = 97.00
        assertThat(row.getTotalAmount()).isEqualByComparingTo("97.00");
        assertThat(row.getOrderCount()).isEqualTo(2L);    // Two ORDER_PAYMENT entries
        assertThat(row.getRefundCount()).isEqualTo(3L);   // Three REFUND_REVERSAL entries
    }
}
