package com.enunas.backend.order;

import com.enunas.backend.product.productlisting.ProductListing;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

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

    // Keep reference for admin/brand queries, but price data is always from snapshot below
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    private ProductListing productListing;

    // --- Price snapshot (immutable at purchase time) ---
    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private String variantSku;

    @Column(nullable = false)
    private String variantColor;

    @Column(nullable = false)
    private String variantSize;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtPurchase;

    @Column(precision = 10, scale = 2)
    private BigDecimal discountPriceAtPurchase;

    @Column(precision = 10, scale = 2)
    private BigDecimal shippingCostAtPurchase;

    @Column(nullable = false)
    private int quantity;

    // effectivePrice = discountPrice if set, else price
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

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
