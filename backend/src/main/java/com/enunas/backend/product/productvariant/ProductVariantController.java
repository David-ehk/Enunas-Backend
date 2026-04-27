package com.enunas.backend.product.productvariant;

import com.enunas.backend.product.dto.ProductVariantDto;
import com.enunas.backend.product.dto.ProductVariantResponseDto;
import com.enunas.backend.product.dto.UpdateProductVariantDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products/{productId}/variants")
@RequiredArgsConstructor
public class ProductVariantController {

    private final ProductVariantService variantService;

    @PostMapping
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductVariantResponseDto> addVariant(
            @PathVariable Long productId,
            @Valid @RequestBody ProductVariantDto dto,
            @AuthenticationPrincipal User creator) {
        return ResponseEntity.status(HttpStatus.CREATED).body(variantService.addVariant(productId, dto, creator));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<List<ProductVariantResponseDto>> getVariants(@PathVariable Long productId) {
        return ResponseEntity.ok(variantService.getVariants(productId));
    }

    @PutMapping("/{variantId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductVariantResponseDto> updateVariant(
            @PathVariable Long productId,
            @PathVariable Long variantId,
            @Valid @RequestBody UpdateProductVariantDto dto,
            @AuthenticationPrincipal User creator) {
        return ResponseEntity.ok(variantService.updateVariant(productId, variantId, dto, creator));
    }

    @DeleteMapping("/{variantId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<Void> deleteVariant(
            @PathVariable Long productId,
            @PathVariable Long variantId,
            @AuthenticationPrincipal User creator) {
        variantService.deleteVariant(productId, variantId, creator);
        return ResponseEntity.noContent().build();
    }
}
