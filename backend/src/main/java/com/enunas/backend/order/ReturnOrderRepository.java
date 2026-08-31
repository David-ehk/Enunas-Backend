package com.enunas.backend.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {

    /**
     * All returns for an order — one per brand. Ordered by id so callers that derive a single
     * order-level view (status aggregation, the deprecated single-return endpoints) are deterministic.
     */
    List<ReturnOrder> findByOrder_IdOrderByIdAsc(Long orderId);

    /**
     * Batch variant of {@link #findByOrder_IdOrderByIdAsc} for list endpoints: every return across
     * a whole page of orders in one query, grouped by order id in
     * {@code OrderService.loadRelations}.
     */
    List<ReturnOrder> findByOrder_IdInOrderByIdAsc(Collection<Long> orderIds);

    Optional<ReturnOrder> findByReturnNumber(String returnNumber);

    /**
     * The still-open (non-REFUNDED) return for this brand on this order, if any. Backs the
     * invariant enforced by the partial unique index in V15 (one ACTIVE return per order+brand):
     * a second request for the same brand merges into this one while it is REQUESTED, or is
     * rejected while it is APPROVED/RECEIVED — never silently creates a second concurrent return.
     */
    Optional<ReturnOrder> findFirstByOrder_IdAndBrand_IdAndStatusNot(
            Long orderId, Long brandId, ReturnStatus excludedStatus);

    /**
     * OrderItem ids on this order that already sit on a return. Used to reject a second return of
     * the same item while still allowing a later return of a different brand's items — the old
     * {@code existsByOrder} guard blocked the whole order and made per-brand returns impossible.
     */
    @Query("SELECT ri.orderItem.id FROM ReturnItem ri WHERE ri.returnOrder.order.id = :orderId")
    List<Long> findReturnedOrderItemIds(@Param("orderId") Long orderId);
}
