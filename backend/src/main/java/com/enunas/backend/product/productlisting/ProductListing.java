package com.enunas.backend.product.productlisting;

import com.enunas.backend.product.Product;
import com.enunas.backend.product.productvariant.ProductVariant;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sales-configuration layer for a variant: price, currency, activation, timing.
 * Stock lives exclusively on {@link ProductVariant}; this entity must never carry stock.
 */
@Entity
@Table(name = "listings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    // price / discountPrice hold the GROSS (customer-facing) values — canonical for display
    // and order math. The matching *Net columns are derived from priceInputMode at write time.
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal discountPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal priceNet;

    @Column(precision = 10, scale = 2)
    private BigDecimal discountPriceNet;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_input_mode")
    private PriceInputMode priceInputMode;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    @Builder.Default
    private boolean active = true;

    private String region;

    private LocalDateTime dropDate;
    private LocalDateTime availableFrom;
    private LocalDateTime availableUntil;

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
        if (!(o instanceof ProductListing other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
    public BigDecimal getCurrentPrice() {
        if (discountPrice != null && discountPrice.compareTo(BigDecimal.ZERO) > 0) {
            return discountPrice;
        }
        return price;
    }

    /** Customer-facing gross used to build an order line: the sale gross if on sale, else the regular gross. */
    public BigDecimal getEffectiveGross() {
        return getCurrentPrice();
    }

    /** Net counterpart of {@link #getEffectiveGross()} — the discount net if on sale, else the regular net. */
    public BigDecimal getEffectiveNet() {
        if (discountPrice != null && discountPrice.compareTo(BigDecimal.ZERO) > 0) {
            return discountPriceNet;
        }
        return priceNet;
    }

    // Convenience: Ist das Listing aktuell aktiv?
    public boolean isCurrentlyActive() {
        if (!active) return false;
        LocalDateTime now = LocalDateTime.now();
        if (availableFrom != null && now.isBefore(availableFrom)) return false;
        if (availableUntil != null && now.isAfter(availableUntil)) return false;
        return true;
    }

}
