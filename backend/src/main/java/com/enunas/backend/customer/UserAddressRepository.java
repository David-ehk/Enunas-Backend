package com.enunas.backend.customer;

import com.enunas.backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    List<UserAddress> findByUserOrderByCreatedAtDesc(User user);

    Optional<UserAddress> findByIdAndUser(Long id, User user);

    boolean existsByUser(User user);
}
