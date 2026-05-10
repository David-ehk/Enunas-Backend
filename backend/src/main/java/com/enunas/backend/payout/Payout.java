package com.enunas.backend.payout;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payouts", indexes = {
    @Index(name = "idx_payout_brand",  columnList = "brand_partner_id"),
    @Index(name = "idx_payout_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_partner_id", nullable = false)
    private Long brandPartnerId;

    /** Net amount wired to the brand's bank account. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /**
     * Portion of payoutBalance kept by the platform to offset outstandingDebt.
     * Stored here so markAsPaid knows exactly how much debt to absorb without
     * re-reading BrandEconomics at a potentially different state.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal debtAbsorbed = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PayoutStatus status = PayoutStatus.PENDING;

    /** Snapshot of the brand's IBAN at generation time. */
    @Column(nullable = false, length = 34)
    private String iban;

    @Column(nullable = false)
    private String bankAccountHolder;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    private LocalDateTime approvedAt;
    private String approvedByAdminEmail;

    private LocalDateTime paidAt;
    private String paidByAdminEmail;

    /** Bank transfer reference set when status moves to PAID. */
    private String externalReference;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
