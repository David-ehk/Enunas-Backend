package com.enunas.backend.brandpartner.brandshippingprofile;

import com.enunas.backend.brandpartner.BrandPartner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandShippingProfileRepository extends JpaRepository<BrandShippingProfile, Long> {

    Optional<BrandShippingProfile> findByBrandPartner(BrandPartner brandPartner);
}
