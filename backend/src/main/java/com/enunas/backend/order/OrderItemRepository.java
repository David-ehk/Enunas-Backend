package com.enunas.backend.order;

import com.enunas.backend.customer.dto.CustomerBrandSpendingDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByVariantId(Long variantId);

    /** True once any variant of this product has ever been ordered. Guards product deletion: the
     *  order record has to outlive the catalogue entry it was sold from (§257 HGB), and the money
     *  snapshots on OrderItem are only readable while the row survives. */
    boolean existsByVariant_Product_Id(Long productId);

    /**
     * Line items of a brand's completed sales (not PENDING/CANCELLED) whose order falls in the
     * period [startUtc, endUtc). Backs the §22f export — one row per supplied item.
     */
    @Query("""
            SELECT oi FROM OrderItem oi
            JOIN oi.order o
            JOIN oi.variant v
            JOIN v.product p
            WHERE p.brand.id = :brandId
              AND o.status <> com.enunas.backend.order.OrderStatus.PENDING
              AND o.status <> com.enunas.backend.order.OrderStatus.CANCELLED
              AND o.createdAt >= :startUtc AND o.createdAt < :endUtc
            ORDER BY o.createdAt ASC, oi.id ASC
            """)
    List<OrderItem> findVat22fLineItems(@Param("brandId") Long brandId,
                                        @Param("startUtc") LocalDateTime startUtc,
                                        @Param("endUtc") LocalDateTime endUtc);

    @Query("""
            SELECT new com.enunas.backend.customer.dto.CustomerBrandSpendingDto(
                i.brandSnapshotName, SUM(i.lineTotal), COUNT(i))
            FROM OrderItem i
            JOIN i.order o
            WHERE o.buyer.id = :userId
              AND o.status IN :statuses
            GROUP BY i.brandSnapshotName
            ORDER BY SUM(i.lineTotal) DESC
            """)
    List<CustomerBrandSpendingDto> findBrandSpendingByUserId(
            @Param("userId") Long userId,
            @Param("statuses") Collection<OrderStatus> statuses);
}
