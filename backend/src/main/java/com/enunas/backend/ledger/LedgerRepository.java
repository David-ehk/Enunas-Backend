package com.enunas.backend.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    boolean existsByOrderIdAndEntryType(Long orderId, LedgerEntryType entryType);

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
}
