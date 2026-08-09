package com.enunas.backend.settlement.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettlementAccountingInputRepository extends JpaRepository<SettlementAccountingInput, Long> {
    Optional<SettlementAccountingInput> findByPeriod(String period);
}
