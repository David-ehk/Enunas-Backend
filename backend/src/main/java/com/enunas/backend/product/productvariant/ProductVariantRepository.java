package com.enunas.backend.product.productvariant;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductId(Long productId);

    void deleteByProductId(Long productId);

    boolean existsByProductColorIdAndSize(Long productColorId, String size);

    /** Prevents duplicate (color, size) when checking against a different variant (for updates). */
    @Query("SELECT COUNT(v) > 0 FROM ProductVariant v " +
           "WHERE v.productColor.id = :colorId AND v.size = :size AND v.id <> :excludeId")
    boolean existsByProductColorIdAndSizeExcluding(
            @Param("colorId") Long colorId,
            @Param("size")    String size,
            @Param("excludeId") Long excludeId);

    /** Atomic decrement — only succeeds if stockQuantity >= quantity. Returns 1 on success, 0 on failure. */
    @Modifying
    @Query("UPDATE ProductVariant v SET v.stockQuantity = v.stockQuantity - :quantity " +
           "WHERE v.id = :id AND v.stockQuantity >= :quantity")
    int decrementStock(@Param("id") Long id, @Param("quantity") int quantity);

    /** Atomic restore — used on cancellation, return-received, and stock adjustments. */
    @Modifying
    @Query("UPDATE ProductVariant v SET v.stockQuantity = v.stockQuantity + :quantity WHERE v.id = :id")
    void restoreStock(@Param("id") Long id, @Param("quantity") int quantity);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM ProductVariant v WHERE v.id = :id")
    Optional<ProductVariant> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT v.stockQuantity FROM ProductVariant v WHERE v.id = :id")
    Optional<Integer> findStockQuantityById(@Param("id") Long id);
}
