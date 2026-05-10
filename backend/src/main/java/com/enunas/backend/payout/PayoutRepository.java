package com.enunas.backend.payout;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {

    boolean existsByBrandPartnerIdAndStatusIn(Long brandPartnerId, List<PayoutStatus> statuses);

    List<Payout> findByBrandPartnerIdOrderByCreatedAtDesc(Long brandPartnerId);

    Page<Payout> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Payout> findByStatusOrderByCreatedAtDesc(PayoutStatus status, Pageable pageable);

    Optional<Payout> findByExternalReference(String externalReference);

    @Query("SELECT COUNT(p) FROM Payout p WHERE p.status = :status")
    long countByStatus(@Param("status") PayoutStatus status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payout p WHERE p.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") PayoutStatus status);
}
