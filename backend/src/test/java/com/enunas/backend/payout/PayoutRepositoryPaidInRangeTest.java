package com.enunas.backend.payout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PayoutRepositoryPaidInRangeTest {

    @Autowired private PayoutRepository payoutRepository;

    private Payout payout(BigDecimal amount, PayoutStatus status, LocalDateTime paidAt) {
        return Payout.builder()
                .brandPartnerId(1L)
                .amount(amount)
                .debtAbsorbed(BigDecimal.ZERO)
                .status(status)
                .iban("DE89370400440532013000")
                .bankAccountHolder("BrandA GmbH")
                .currency("EUR")
                .paidAt(paidAt)
                .externalReference(status == PayoutStatus.PAID ? "QONTO-" + amount : null)
                .build();
    }

    @Test
    void sumsOnlyPaidPayoutsWithinRange() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        payoutRepository.save(payout(new BigDecimal("97.58"), PayoutStatus.PAID,
                LocalDateTime.of(2026, 8, 15, 10, 0)));       // in range
        payoutRepository.save(payout(new BigDecimal("50.00"), PayoutStatus.PENDING,
                LocalDateTime.of(2026, 8, 16, 10, 0)));       // not PAID, excluded
        payoutRepository.save(payout(new BigDecimal("30.00"), PayoutStatus.PAID,
                LocalDateTime.of(2026, 9, 2, 10, 0)));        // out of range

        assertThat(payoutRepository.sumPaidAmountInRange(start, end)).isEqualByComparingTo("97.58");
        assertThat(payoutRepository.countPaidPayoutsInRange(start, end)).isEqualTo(1L);

        LocalDateTime emptyStart = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime emptyEnd = LocalDateTime.of(2026, 2, 1, 0, 0);
        assertThat(payoutRepository.sumPaidAmountInRange(emptyStart, emptyEnd)).isEqualByComparingTo("0");
        assertThat(payoutRepository.countPaidPayoutsInRange(emptyStart, emptyEnd)).isEqualTo(0L);
    }
}
