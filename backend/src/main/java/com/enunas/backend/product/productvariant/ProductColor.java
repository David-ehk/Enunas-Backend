package com.enunas.backend.product.productvariant;

import com.enunas.backend.product.Product;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Color-level SKU ownership. One ProductColor represents one colorway of a product;
 * all size variants under that colorway share this SKU.
 */
@Entity
@Table(
    name = "product_colors",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_product_colors_sku",           columnNames = {"sku"}),
        @UniqueConstraint(name = "uq_product_colors_product_color", columnNames = {"product_id", "color"})
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductColor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 8)
    private String sku;

    @Column(nullable = false)
    private String color;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @OneToMany(mappedBy = "productColor", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProductVariant> variants = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductColor other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
