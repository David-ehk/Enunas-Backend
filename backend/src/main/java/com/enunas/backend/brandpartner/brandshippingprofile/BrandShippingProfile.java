package com.enunas.backend.brandpartner.brandshippingprofile;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "brand_shipping_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandShippingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, unique = true)
    private BrandPartner brandPartner;

    private String originCountry;

    @Builder.Default
    private boolean handlesOwnShipping = false;

    private Integer avgShippingDays;

    /**
     * Flat shipping cost charged once per brand per order, in {@link #currency}. These are two
     * DIFFERENT, DELIBERATELY DISTINCT outcomes — {@code ShippingCostService} names and persists
     * which one applied on every order, rather than leaving it to be inferred from the number:
     * <ul>
     *   <li>{@code null} — not configured. The platform default rate applies.</li>
     *   <li>{@code 0.00} — explicit free shipping (a campaign, a premium-brand perk, etc.).</li>
     *   <li>{@code > 0.00} — this brand's flat rate.</li>
     * </ul>
     */
    @Column(precision = 10, scale = 2)
    private BigDecimal shippingCost;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "EUR";
}
