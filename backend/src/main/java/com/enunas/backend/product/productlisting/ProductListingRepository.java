package com.enunas.backend.product.productlisting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {

    List<ProductListing> findByProductId(Long productId);

    List<ProductListing> findByVariantId(Long variantId);


    Optional<ProductListing> findByVariantIdAndActiveTrue(Long variantId);

    //  not nessary right now stating only in Germany List<ProductListing> findByRegionAndActive(String region, Boolean active);

    List<ProductListing> findByProductIdAndActive(Long productId, boolean active);

    List<ProductListing> findByRegionAndActive(String region, boolean active);

    @Query("SELECT l FROM ProductListing l WHERE l.variant.id = :variantId AND l.active = true AND l.availableFrom <= CURRENT_TIMESTAMP AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP)")
    Optional<ProductListing> findCurrentlyActiveByVariantId(@Param("variantId") Long variantId);

    // Für regionale Listings (wenn region null = alle Regionen)
    @Query("SELECT l FROM ProductListing l WHERE l.variant.id = :variantId AND l.active = true AND (l.region IS NULL OR l.region = :region) AND l.availableFrom <= CURRENT_TIMESTAMP AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP)")
    Optional<ProductListing> findCurrentlyActiveByVariantIdAndRegion(@Param("variantId") Long variantId, @Param("region") String region);
}
