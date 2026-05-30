package com.enunas.backend.discount;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<DiscountCode> findByBrand_IdOrderByCreatedAtDesc(Long brandId);

    List<DiscountCode> findAllByOrderByCreatedAtDesc();

    /**
     * Atomic usage reservation — mirrors {@code ProductVariantRepository.decrementStock}.
     * Only succeeds while the code is active and below its usage limit (null = unlimited).
     * Returns 1 on success, 0 if the code is inactive or exhausted (lost the race).
     */
    @Modifying
    @Query("UPDATE DiscountCode d SET d.usedCount = d.usedCount + 1 " +
           "WHERE d.id = :id AND d.active = true " +
           "AND (d.maxUses IS NULL OR d.usedCount < d.maxUses)")
    int reserveUsage(@Param("id") Long id);
}
