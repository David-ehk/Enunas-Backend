package com.enunas.backend.ledger;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_ledger_order_id",    columnList = "order_id"),
    @Index(name = "idx_ledger_brand_id",    columnList = "brand_partner_id"),
    @Index(name = "idx_ledger_eligible_at", columnList = "payout_eligible_at"),
    @Index(name = "idx_ledger_status",      columnList = "status")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    // Nullable: REFUND_REVERSAL entries are per-brand aggregates, not per-item.
    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "brand_partner_id", nullable = false)
    private Long brandPartnerId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal platformFee;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal brandPayout;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LedgerEntryStatus status = LedgerEntryStatus.PENDING_RELEASE;

    @Column(nullable = false)
    private LocalDateTime payoutEligibleAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean movedToAvailable = false;

    // For REFUND_REVERSAL entries: references the original ORDER_PAYMENT entry being reversed.
    @Column(name = "reversal_of_entry_id")
    private Long reversalOfEntryId;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
