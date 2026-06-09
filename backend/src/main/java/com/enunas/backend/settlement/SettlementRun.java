package com.enunas.backend.settlement;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable marker that a brand's calendar month has been settled. Freezes the aggregated
 * commission/payout figures at settlement time so later ledger changes (e.g. a refund booked
 * into a closed period) never alter the invoiced/paid amount. UNIQUE (brand_id, period)
 * prevents double settlement.
 */
@Entity
@Table(
    name = "settlement_runs",
    uniqueConstraints = @UniqueConstraint(name = "uq_settlement_brand_period",
            columnNames = {"brand_id", "period"})
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    /** Calendar month, 'YYYY-MM' (Europe/Berlin). */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Column(name = "invoice_reference", length = 100)
    private String invoiceReference;

    @Column(name = "commission_net", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionNet;

    @Column(name = "commission_vat", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionVat;

    @Column(name = "payout_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal payoutAmount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
