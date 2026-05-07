package com.enunas.backend.brandpartner.brandeconomics;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "brand_economics")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandEconomics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, unique = true)
    private BrandPartner brandPartner;

    @Builder.Default
    private BigDecimal defaultCommissionRate = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal payoutBalance = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal lifetimeRevenue = BigDecimal.ZERO;

    // Earned but within hold window — not yet eligible for payout.
    @Builder.Default
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    // Cumulative amount transferred out to the brand.
    @Builder.Default
    private BigDecimal paidOutTotal = BigDecimal.ZERO;

    // Refund amount that exceeded pendingBalance + payoutBalance (brand owes platform).
    @Builder.Default
    private BigDecimal outstandingDebt = BigDecimal.ZERO;

    // Mollie Connect: org_xxxxx from the brand's Mollie account. Null = not yet connected.
    private String mollieOrganizationId;
}
