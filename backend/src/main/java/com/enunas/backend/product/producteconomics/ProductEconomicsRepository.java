package com.enunas.backend.product.producteconomics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductEconomicsRepository extends JpaRepository<ProductEconomics, Long> {

    Optional<ProductEconomics> findByProductId(Long productId);
}
