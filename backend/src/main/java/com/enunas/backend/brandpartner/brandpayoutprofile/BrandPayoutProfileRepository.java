package com.enunas.backend.brandpartner.brandpayoutprofile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandPayoutProfileRepository extends JpaRepository<BrandPayoutProfile, Long> {

    Optional<BrandPayoutProfile> findByBrandPartner_Id(Long brandId);
}
