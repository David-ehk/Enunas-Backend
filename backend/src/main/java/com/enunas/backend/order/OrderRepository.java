package com.enunas.backend.order;

import com.enunas.backend.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByBuyerOrderByCreatedAtDesc(User buyer, Pageable pageable);

    /**
     * Pessimistic row lock used by the payment webhook to serialize the PENDING→PAID transition.
     * Concurrent duplicate webhooks block here until the winner commits, then see status = PAID and
     * no-op — preventing a double booking / double payout.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    Optional<Order> findByOrderNumber(String orderNumber);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @Query("SELECT DISTINCT o FROM Order o " +
            "JOIN o.items i " +
            "JOIN i.variant v " +
            "JOIN v.product p " +
            "WHERE p.creator.id = :creatorId")
    Page<Order> findByBrandPartnerCreatorId(@Param("creatorId") Long creatorId, Pageable pageable);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);
}
