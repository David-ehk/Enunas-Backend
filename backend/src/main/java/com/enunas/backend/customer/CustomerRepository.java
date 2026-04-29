package com.enunas.backend.customer;

import com.enunas.backend.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByUser(User user);

    boolean existsByUser(User user);

    Page<Customer> findAll(Pageable pageable);
    Optional<Customer> findById(Long id);
}
