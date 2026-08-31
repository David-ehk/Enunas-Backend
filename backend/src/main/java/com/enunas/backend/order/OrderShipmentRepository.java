package com.enunas.backend.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {

    /** Every brand's shipment row on an order — one per brand, ordered for deterministic display. */
    List<OrderShipment> findByOrder_IdOrderByIdAsc(Long orderId);

    /**
     * Batch variant of {@link #findByOrder_IdOrderByIdAsc} for list endpoints: every brand's
     * shipment row across a whole page of orders in one query, grouped by order id in
     * {@code OrderService.loadRelations}.
     */
    List<OrderShipment> findByOrder_IdInOrderByIdAsc(Collection<Long> orderIds);

    /** The find-or-create lookup {@link OrderService} uses before recording a brand's own
     *  ship/problem event — at most one row per (order, brand), enforced by the DB unique index. */
    Optional<OrderShipment> findByOrder_IdAndBrand_Id(Long orderId, Long brandId);
}
