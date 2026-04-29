package com.enunas.backend.order;

import com.enunas.backend.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByBuyerOrderByCreatedAtDesc(User buyer, Pageable pageable);

    Optional<Order> findByOrderNumber(String orderNumber);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN o.items i " +
            "JOIN i.variant v " +
            "JOIN v.product p " +
            "WHERE p.creator.id = :creatorId")
    Page<Order> findByBrandPartnerCreatorId(@Param("creatorId") Long creatorId, Pageable pageable);
}
