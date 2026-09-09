package com.enunas.backend.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderByDisplayOrderAsc(Long productId);

    Optional<ProductImage> findByProductIdAndProductColorIdAndPrimary(
            Long productId, Long productColorId, boolean primary);

    @Query("""
            SELECT i FROM ProductImage i
            LEFT JOIN FETCH i.productColor
            WHERE i.product.id = :productId
              AND (:colorId IS NULL OR i.productColor.id = :colorId OR i.productColor IS NULL)
            """)
    List<ProductImage> findForProductAndOptionalColour(
            @Param("productId") Long productId, @Param("colorId") Long colorId);

    void deleteByProductId(Long productId);
}
