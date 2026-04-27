package com.enunas.backend.product.productanalytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductAnalyticsRepository extends JpaRepository<ProductAnalytics, Long> {

    Optional<ProductAnalytics> findByProductId(Long productId);
}
