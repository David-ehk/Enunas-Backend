package com.enunas.backend.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    /** Projection used by reconciliation queries. */
    interface BrandBalanceSummary {
        Long getBrandPartnerId();
        BigDecimal getTotal();
    }

    /** Per-brand period aggregate for the monthly settlement report. */
    interface PeriodAggregate {
        Long getBrandId();
        BigDecimal getCommissionNet();
        BigDecimal getCommissionVat();
        BigDecimal getPayoutAmount();
        BigDecimal getTotalAmount();
        Long getOrderCount();
        Long getRefundCount();
    }

    /**
     * Sums each brand's ledger figures over a period (UTC bounds, end-exclusive), counting
     * ORDER_PAYMENT and REFUND_REVERSAL entries by their own created_at. REFUND_REVERSAL rows are
     * stored negative, so a plain period-filtered SUM nets refunds against sales automatically.
     */
    @Query("""
           SELECT le.brandPartnerId AS brandId,
                  COALESCE(SUM(le.commissionNet), 0) AS commissionNet,
                  COALESCE(SUM(le.commissionVat), 0) AS commissionVat,
                  COALESCE(SUM(le.brandPayout), 0)   AS payoutAmount,
                  COALESCE(SUM(le.totalAmount), 0)   AS totalAmount,
                  SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT   THEN 1 ELSE 0 END) AS orderCount,
                  SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL THEN 1 ELSE 0 END) AS refundCount
           FROM LedgerEntry le
           WHERE le.createdAt >= :startUtc AND le.createdAt < :endUtc
             AND le.entryType IN (com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT,
                                  com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL)
           GROUP BY le.brandPartnerId
           """)
    List<PeriodAggregate> aggregateByBrandForPeriod(
            @Param("startUtc") LocalDateTime startUtc,
            @Param("endUtc") LocalDateTime endUtc);

    boolean existsByOrderIdAndEntryType(Long orderId, LedgerEntryType entryType);

    boolean existsByExternalReferenceIdAndEntryType(String externalReferenceId, LedgerEntryType entryType);

    @Query("""
           SELECT le FROM LedgerEntry le
           WHERE le.orderId = :orderId
             AND le.brandPartnerId = :brandPartnerId
             AND le.entryType = com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT
             AND le.status <> com.enunas.backend.ledger.LedgerEntryStatus.REVERSED
           ORDER BY le.id ASC
           """)
    List<LedgerEntry> findActivePaymentEntriesByOrderAndBrand(
            @Param("orderId") Long orderId,
            @Param("brandPartnerId") Long brandPartnerId);

    @Query("""
           SELECT le FROM LedgerEntry le
           WHERE le.status = com.enunas.backend.ledger.LedgerEntryStatus.PENDING_RELEASE
             AND le.payoutEligibleAt <= :now
             AND le.movedToAvailable = false
           """)
    List<LedgerEntry> findReleasableEntries(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("""
           UPDATE LedgerEntry le
           SET le.status = com.enunas.backend.ledger.LedgerEntryStatus.AVAILABLE,
               le.movedToAvailable = true
           WHERE le.id IN :ids
           """)
    void markAsAvailable(@Param("ids") List<Long> ids);

    List<LedgerEntry> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    // ===== Reconciliation (all-brand aggregates) =====

    @Query("""
           SELECT le.brandPartnerId AS brandPartnerId, SUM(le.brandPayout) AS total
           FROM LedgerEntry le
           WHERE le.status = com.enunas.backend.ledger.LedgerEntryStatus.AVAILABLE
           GROUP BY le.brandPartnerId
           """)
    List<BrandBalanceSummary> sumAvailablePayoutPerBrand();

    @Query("""
           SELECT le.brandPartnerId AS brandPartnerId, SUM(le.brandPayout) AS total
           FROM LedgerEntry le
           WHERE le.status = com.enunas.backend.ledger.LedgerEntryStatus.PENDING_RELEASE
           GROUP BY le.brandPartnerId
           """)
    List<BrandBalanceSummary> sumPendingPayoutPerBrand();

    // ===== Reconciliation (per-brand, used for drift check and rebuild) =====

    @Query("SELECT SUM(le.brandPayout) FROM LedgerEntry le WHERE le.brandPartnerId = :brandId AND le.entryType = com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT")
    Optional<BigDecimal> sumOrderPaymentsForBrand(@Param("brandId") Long brandId);

    @Query("SELECT SUM(le.brandPayout) FROM LedgerEntry le WHERE le.brandPartnerId = :brandId AND le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL")
    Optional<BigDecimal> sumRefundReversalsForBrand(@Param("brandId") Long brandId);

    @Query("SELECT SUM(le.brandPayout) FROM LedgerEntry le WHERE le.brandPartnerId = :brandId AND le.entryType = com.enunas.backend.ledger.LedgerEntryType.PAYOUT_TRANSFER")
    Optional<BigDecimal> sumPayoutTransfersForBrand(@Param("brandId") Long brandId);

    @Query("SELECT SUM(le.brandPayout) FROM LedgerEntry le WHERE le.brandPartnerId = :brandId AND le.status = com.enunas.backend.ledger.LedgerEntryStatus.PENDING_RELEASE")
    Optional<BigDecimal> sumPendingReleaseForBrand(@Param("brandId") Long brandId);

    @Query("SELECT SUM(le.brandPayout) FROM LedgerEntry le WHERE le.brandPartnerId = :brandId AND le.status = com.enunas.backend.ledger.LedgerEntryStatus.AVAILABLE")
    Optional<BigDecimal> sumAvailableForBrand(@Param("brandId") Long brandId);
}
