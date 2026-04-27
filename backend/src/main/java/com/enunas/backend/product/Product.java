package com.enunas.backend.product;

import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.ProductVideo;
import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.product.productanalytics.ProductAnalytics;
import com.enunas.backend.product.producteconomics.ProductEconomics;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "products")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false)
    private BrandPartner brand;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String inspirationStory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProductCategory category = ProductCategory.CLOTHING;

    @Enumerated(EnumType.STRING)
    private ProductCatalogueCategory catalogueCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Gender gender = Gender.UNISEX;

    private String material;

    private String originCountry;

    @Column(columnDefinition = "TEXT")
    private String careInstructions;

    // Not unique — multiple products can share a collection name; use CollectionEntity later
    private String collectionName;

    private LocalDate releaseDate;

    @Builder.Default
    private int returnPeriodDays = 14;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProductStatus status = ProductStatus.PENDING_REVIEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private ProductEconomics economics;

    @OneToOne(mappedBy = "product", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private ProductAnalytics analytics;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ProductVariant> variants = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ProductImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ProductVideo> videos = new ArrayList<>();

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

    // ===== Collection access =====

    public List<ProductVariant> getVariants() {
        return variants == null ? List.of() : Collections.unmodifiableList(variants);
    }

    public List<ProductImage> getImages() {
        return images == null ? List.of() : Collections.unmodifiableList(images);
    }

    public List<ProductVideo> getVideos() {
        return videos == null ? List.of() : Collections.unmodifiableList(videos);
    }

    public void addVariant(ProductVariant variant) {
        if (variants == null) variants = new ArrayList<>();
        variants.add(variant);
        variant.setProduct(this);
    }

    public void addImage(ProductImage image) {
        if (images == null) images = new ArrayList<>();
        images.add(image);
        image.setProduct(this);
    }

    public void addVideo(ProductVideo video) {
        if (videos == null) videos = new ArrayList<>();
        videos.add(video);
        video.setProduct(this);
    }

    // ===== equals / hashCode =====

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
