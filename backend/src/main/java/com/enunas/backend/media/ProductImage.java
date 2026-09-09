package com.enunas.backend.media;

import com.enunas.backend.product.Product;
import com.enunas.backend.product.productvariant.ProductColor;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "product_images")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** The colourway this image is specific to. Null = shared: shown for every colourway. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_color_id")
    private ProductColor productColor;

    @Column(nullable = false)
    private String storageKey;

    private String altText;

    @Builder.Default
    @Column(name = "is_primary")
    private boolean primary = false;

    @Builder.Default
    private int displayOrder = 0;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
