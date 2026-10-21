package com.enunas.backend.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** Sum of units already on a return (any return, any status), per order item of this order. */
    @Query("SELECT ri.orderItem.id, SUM(ri.quantityReturned) FROM ReturnItem ri "
            + "WHERE ri.returnOrder.order.id = :orderId GROUP BY ri.orderItem.id")
    List<Object[]> returnedQuantityByItem(@Param("orderId") Long orderId);

    default Map<Long, Integer> returnedUnits(Long orderId) {
        Map<Long, Integer> m = new HashMap<>();
        for (Object[] row : returnedQuantityByItem(orderId)) {
            m.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return m;
    }

    /**
     * Returns whose refund has been claimed (any refund state, including an unfinished claim), plus
     * returns refunded before V37, which carry refund_state REFUNDED but no claim key.
     */
    @Query("SELECT r FROM ReturnOrder r WHERE r.order.id = :orderId AND (r.refundClaimKey IS NOT NULL "
            + "OR r.refundState = com.enunas.backend.order.ReturnRefundState.REFUNDED)")
    List<ReturnOrder> findClaimedByOrderId(@Param("orderId") Long orderId);

    // Refund overview (admin). Newest first.
    @Query("SELECT r FROM ReturnOrder r WHERE r.status = com.enunas.backend.order.ReturnStatus.RECEIVED "
            + "AND r.refundClaimKey IS NULL ORDER BY r.id DESC")
    Page<ReturnOrder> findRefundDue(Pageable pageable);

    /** Claimed with no state yet = the attempt is in flight (a resend keeps its old refund id, so don't test that). */
    @Query("SELECT r FROM ReturnOrder r WHERE r.refundClaimKey IS NOT NULL AND r.refundState IS NULL "
            + "ORDER BY r.id DESC")
    Page<ReturnOrder> findRefundInProgress(Pageable pageable);

    @Query("SELECT r FROM ReturnOrder r WHERE r.refundState = :state ORDER BY r.id DESC")
    Page<ReturnOrder> findByRefundState(@Param("state") ReturnRefundState state, Pageable pageable);
}
