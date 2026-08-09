package com.enunas.backend.order;

import com.enunas.backend.shipping.ShippingCalculationMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable per-(order, brand) shipping charge, frozen at order creation — never updated
 * afterward. Represents exactly what the customer paid for that brand's shipment, even if the
 * brand's {@link com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile} rate
 * changes later.
 */
@Entity
@Table(name = "order_shipping_snapshots")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderShippingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "brand_partner_id", nullable = false)
    private Long brandPartnerId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_method", nullable = false, length = 30)
    private ShippingCalculationMethod calculationMethod;

    @Column(name = "rule_version", nullable = false, length = 20)
    private String ruleVersion;

    /** Debugging traceability only — never read back into any calculation. */
    @Column(name = "brand_shipping_profile_id")
    private Long brandShippingProfileId;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
