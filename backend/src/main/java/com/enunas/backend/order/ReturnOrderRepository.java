package com.enunas.backend.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {

    Optional<ReturnOrder> findByOrder(Order order);

    Optional<ReturnOrder> findByReturnNumber(String returnNumber);
}
