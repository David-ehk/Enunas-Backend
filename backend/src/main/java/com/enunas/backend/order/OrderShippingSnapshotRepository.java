package com.enunas.backend.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface OrderShippingSnapshotRepository extends JpaRepository<OrderShippingSnapshot, Long> {

    List<OrderShippingSnapshot> findByOrderIdOrderByIdAsc(Long orderId);

    /**
     * Batch variant for list endpoints: every snapshot for a whole page of orders in one query.
     * {@link OrderService} groups the result by orderId rather than issuing this lookup once per
     * order (see {@code OrderService.loadRelations} — a 20-order page cost 20 round trips here).
     */
    List<OrderShippingSnapshot> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    /** Sum of the shipping the customer paid this brand on this order. */
    default BigDecimal brandShippingTotal(Long orderId, Long brandId) {
        return findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(s -> brandId.equals(s.getBrandPartnerId()))
                .map(OrderShippingSnapshot::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
