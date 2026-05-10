package com.enunas.backend.brandpartner.brandeconomics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandEconomicsRepository extends JpaRepository<BrandEconomics, Long> {

    Optional<BrandEconomics> findByBrandPartner_Id(Long brandPartnerId);

    @Query("SELECT e FROM BrandEconomics e WHERE e.outstandingDebt > 0")
    List<BrandEconomics> findBrandsWithOutstandingDebt();
}
