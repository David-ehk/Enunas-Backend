package com.enunas.backend.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    /**
     * Batch lookup for a whole page of orders — {@code OrderService.loadRelations} uses this to put
     * the provider payment id on each order DTO without going back to the database per order.
     * {@code payments.order_id} is unique, so this is at most one row per order id.
     */
    List<Payment> findByOrderIdIn(Collection<Long> orderIds);
}
