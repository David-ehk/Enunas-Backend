package com.enunas.backend.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {

    boolean existsByBrandIdAndPeriod(Long brandId, String period);

    List<SettlementRun> findByPeriod(String period);
}
