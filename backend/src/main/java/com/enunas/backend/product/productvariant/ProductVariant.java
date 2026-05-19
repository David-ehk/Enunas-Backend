package com.enunas.backend.product.productvariant;

import com.enunas.backend.product.Product;
import jakarta.persistence.*;
import lombok.*;

/**
 * A size-level variant of a product. SKU and color are owned by {@link ProductColor};
 * this entity owns stock and weight for a specific size within that colorway.
 *
 * Unique per (product_color, size) — enforced by DB constraint and service-layer validation.
 */
@Entity
@Table(
    name = "product_variants",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_product_variants_color_size",
        columnNames = {"product_color_id", "size"}
    )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_color_id", nullable = false)
    private ProductColor productColor;

    private String size;

    @Column(nullable = false)
    private Integer stockQuantity = 0;

    private Integer weightGrams;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // ===== Convenience delegation — SKU and color live on ProductColor =====

    public String getSku() {
        return productColor != null ? productColor.getSku() : null;
    }

    public String getColor() {
        return productColor != null ? productColor.getColor() : null;
    }

    // ===== Stock helpers =====

    public boolean hasStock(int requestedQuantity) {
        return stockQuantity >= requestedQuantity;
    }

    public void decrementStock(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (!hasStock(quantity)) throw new IllegalStateException("Insufficient stock for variant " + id);
        this.stockQuantity -= quantity;
    }

    public void restoreStock(int quantity) {
        this.stockQuantity += quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductVariant other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
