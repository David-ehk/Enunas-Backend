package com.enunas.backend.discount;

import com.enunas.backend.discount.dto.CreateDiscountDto;
import com.enunas.backend.discount.dto.DiscountResponseDto;
import com.enunas.backend.discount.dto.UpdateDiscountDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Discount-code endpoints. Routing keeps the two audiences apart:
 *  - {@code /admin/discounts} — ROLE_ADMIN, mints marketplace-wide ADMIN codes.
 *  - {@code /brand/discounts}  — ROLE_BRAND_PARTNER, mints BRAND codes scoped to the caller's
 *    own brand. The type is fixed by the endpoint and never read from the body.
 */
@RestController
@RequiredArgsConstructor
public class DiscountController {

    private final DiscountService discountService;

    // ===== Admin =====

    @PostMapping("/admin/discounts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DiscountResponseDto> createAdminDiscount(
            @Valid @RequestBody CreateDiscountDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(discountService.createAdminDiscount(dto, admin));
    }

    @GetMapping("/admin/discounts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DiscountResponseDto>> getAllDiscounts() {
        return ResponseEntity.ok(discountService.getAllDiscounts());
    }

    @PutMapping("/admin/discounts/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DiscountResponseDto> updateAdminDiscount(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDiscountDto dto) {
        return ResponseEntity.ok(discountService.updateAsAdmin(id, dto));
    }

    // ===== Brand partner (own codes only) =====

    @PostMapping("/brand/discounts")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<DiscountResponseDto> createBrandDiscount(
            @Valid @RequestBody CreateDiscountDto dto,
            @AuthenticationPrincipal User brandPartner) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(discountService.createBrandDiscount(dto, brandPartner));
    }

    @GetMapping("/brand/discounts")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<List<DiscountResponseDto>> getMyDiscounts(
            @AuthenticationPrincipal User brandPartner) {
        return ResponseEntity.ok(discountService.getMyDiscounts(brandPartner));
    }

    @PutMapping("/brand/discounts/{id}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<DiscountResponseDto> updateBrandDiscount(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDiscountDto dto,
            @AuthenticationPrincipal User brandPartner) {
        return ResponseEntity.ok(discountService.updateAsBrand(id, dto, brandPartner));
    }
}
