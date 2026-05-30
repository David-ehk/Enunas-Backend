package com.enunas.backend.discount;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A redeemable discount code. The {@link DiscountType} fixes the cost-absorption model; a code
 * never changes type. Historical orders snapshot the relevant values onto Order/OrderItem at
 * checkout, so a code can be edited or deactivated later without altering past orders.
 *
 * Brand ownership: {@code brand} is null for ADMIN codes (marketplace-wide) and required for
 * BRAND codes (the only brand whose products the code may discount).
 */
@Entity
@Table(name = "discount_codes")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DiscountCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored upper-cased; matched case-insensitively at checkout. */
    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType type;

    /** Discount fraction, e.g. 0.1000 for 10%. Validated against {@link DiscountType#getMaxPercent()}. */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal percent;

    /** Null for ADMIN codes. Required for BRAND codes — the ownership/scope anchor. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private BrandPartner brand;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    /** Null = unlimited redemptions; 1 = single-use. */
    private Integer maxUses;

    @Column(nullable = false)
    @Builder.Default
    private Integer usedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Audit: the user (admin or brand partner) who created the code. */
    private Long createdByUserId;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiscountCode other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
