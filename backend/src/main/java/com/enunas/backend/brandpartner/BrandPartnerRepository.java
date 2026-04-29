package com.enunas.backend.brandpartner;

import com.enunas.backend.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BrandPartnerRepository extends JpaRepository<BrandPartner, Long> {

    Optional<BrandPartner> findByUser(User user);

    Optional<BrandPartner> findByBrandName(String brandName);

    boolean existsByBrandName(String brandName);

    Optional<BrandPartner> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<BrandPartner> findAll(Pageable pageable);
    List<BrandPartner> findByStatus(BrandStatus status);
}
