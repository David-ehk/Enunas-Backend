package com.enunas.backend.product;

import com.enunas.backend.product.dto.CreateProductDto;
import com.enunas.backend.product.dto.ProductResponseDto;
import com.enunas.backend.product.dto.UpdateProductDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping("/create")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductResponseDto> createProduct(
            @Valid @RequestBody CreateProductDto dto,
            @AuthenticationPrincipal User creator) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(dto, creator));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<ProductResponseDto> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    @GetMapping("/sku/{sku}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<ProductResponseDto> getProductBySku(@PathVariable String sku) {
        return ResponseEntity.ok(productService.getProductBySku(sku));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<Page<ProductResponseDto>> getAllProducts(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productService.getActiveProducts(pageable));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<Page<ProductResponseDto>> search(
            @RequestParam String keyword,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productService.search(keyword, pageable));
    }

    @GetMapping("/category/{category}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<Page<ProductResponseDto>> getByCategory(
            @PathVariable ProductCategory category,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productService.getProductsByCategory(category, pageable));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<List<ProductResponseDto>> getMyProducts(@AuthenticationPrincipal User creator) {
        return ResponseEntity.ok(productService.getMyProducts(creator));
    }

    @PutMapping("/update/{id}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductResponseDto> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductDto dto,
            @AuthenticationPrincipal User creator) {
        return ResponseEntity.ok(productService.updateProduct(id, dto, creator));
    }

    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable Long id,
            @AuthenticationPrincipal User creator) {
        productService.deleteProduct(id, creator);
        return ResponseEntity.noContent().build();
    }
}
