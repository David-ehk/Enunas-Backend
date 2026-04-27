package com.enunas.backend.product.productlisting;

import com.enunas.backend.product.Product;
import com.enunas.backend.product.productvariant.ProductVariant;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price; // brutto

    @Column(precision = 10, scale = 2)
    private BigDecimal discountPrice;

    private BigDecimal shippingCost; // MVP vlt noch nicht Wichtig

    // Auch noch kein Komplexes VAT Modell alles in Deutschland vor erst

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    // Sale-available units for this listing (region/drop); decremented on order placement
    // ProductVariant.stockQuantity is the total physical inventory
    @Builder.Default
    private int stock = 0;

    @Builder.Default
    private boolean active = true;

    private String region;

    private LocalDateTime dropDate;

    //Brandpartner Drop
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
}
