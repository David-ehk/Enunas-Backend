package com.enunas.backend.order;

import com.enunas.backend.customer.dto.CustomerBrandSpendingDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByVariantId(Long variantId);

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
            @Param("statuses") List<OrderStatus> statuses);
}
