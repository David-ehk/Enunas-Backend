package com.enunas.backend.product.productvariant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductColorRepository extends JpaRepository<ProductColor, Long> {

    Optional<ProductColor> findByProductIdAndColor(Long productId, String color);

    Optional<ProductColor> findBySku(String sku);

    boolean existsBySku(String sku);

    List<ProductColor> findByProductId(Long productId);

    void deleteByProductId(Long productId);
}
