package com.enunas.backend.product.productlisting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {

    List<ProductListing> findByProductId(Long productId);

    List<ProductListing> findByVariantId(Long variantId);

    List<ProductListing> findByProductIdAndActive(Long productId, boolean active);

    List<ProductListing> findByRegionAndActive(String region, boolean active);

    // Atomic decrement — only succeeds if stock >= quantity. Returns 1 on success, 0 on insufficient stock.
    @Modifying
    @Query("UPDATE ProductListing l SET l.stock = l.stock - :quantity WHERE l.id = :id AND l.stock >= :quantity AND l.active = true")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    // Atomic restore — used on order cancellation
    @Modifying
    @Query("UPDATE ProductListing l SET l.stock = l.stock + :quantity WHERE l.id = :id")
    void restoreStock(@Param("id") Long id, @Param("quantity") int quantity);
}
