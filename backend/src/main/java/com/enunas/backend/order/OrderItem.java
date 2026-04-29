package com.enunas.backend.order;

import com.enunas.backend.product.productvariant.ProductVariant;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Line item in an Order. Owns its own price/variant snapshot — the variant FK is the only
 * link back into the catalog. Listings can be deleted without affecting historical orders.
 *
 * Ownership path: OrderItem → ProductVariant → Product → Brand
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    // --- Purchase snapshot (immutable, prevents future changes from affecting history) ---
    @Column(nullable = false)
    private String productSnapshotName;

    @Column(nullable = false)
    private String variantSnapshotSku;

    @Column(nullable = false)
    private String variantSnapshotColor;

    @Column(nullable = false)
    private String variantSnapshotSize;

    private String brandSnapshotName;

    // --- Price snapshot at purchase time ---
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtPurchase;      // Final price paid (after discount)

    @Column(precision = 10, scale = 2)
    private BigDecimal discountPriceAtPurchase; // Original discount if any

    @Column(nullable = false)
    private Integer quantity;

    // --- Calculated fields ---
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;  // priceAtPurchase * quantity (set before save)

    // Convenience for ownership (no DB column - transient)
    public Long getBrandId() {
        // Path: OrderItem → Variant → Product → Brand/Creator
        return variant.getProduct().getBrand().getId();
    }

    // Helper to calculate line total
    public void calculateLineTotal() {
        if (priceAtPurchase != null && quantity != null) {
            this.lineTotal = priceAtPurchase.multiply(BigDecimal.valueOf(quantity));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderItem other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}