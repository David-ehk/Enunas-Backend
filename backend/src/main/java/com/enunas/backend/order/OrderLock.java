package com.enunas.backend.order;

import com.enunas.backend.exception.OrderNotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Takes the orders row lock on a freshly read order. Open-in-view keeps ONE Hibernate session across
 * a multi-phase request, so a lock query alone can hand back an instance an earlier phase already
 * read (lock taken, fields stale) even after a concurrent writer committed. Clearing first makes the
 * order — and everything reachable from it — come from the database. Call it before touching the
 * order, never after loading entities you still mean to use.
 */
@Component
@RequiredArgsConstructor
class OrderLock {

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;

    Order lockFresh(Long orderId) {
        entityManager.clear();
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));
    }
}
