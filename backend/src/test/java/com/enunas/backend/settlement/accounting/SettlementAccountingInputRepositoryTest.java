package com.enunas.backend.settlement.accounting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SettlementAccountingInputRepositoryTest {

    @Autowired private SettlementAccountingInputRepository repository;

    @Test
    void savesAndFindsByPeriod() {
        repository.save(SettlementAccountingInput.builder()
                .period("2026-08")
                .mollieFees(new BigDecimal("35.70"))
                .mollieFeesIncludedInActualPayout(false)
                .payoutReference("stl_test123")
                .mollieSettlementDate(LocalDate.of(2026, 8, 8))
                .enteredByAdminEmail("admin@it.local")
                .enteredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        SettlementAccountingInput found = repository.findByPeriod("2026-08").orElseThrow();
        assertThat(found.getMollieFees()).isEqualByComparingTo("35.70");
        assertThat(found.getMollieFeesIncludedInActualPayout()).isFalse();
        assertThat(found.getPayoutReference()).isEqualTo("stl_test123");

        assertThat(repository.findByPeriod("2026-09")).isEmpty();
    }
}
