package com.enunas.backend.product.productlisting;

import com.enunas.backend.product.productlisting.dto.CreateListingDto;
import com.enunas.backend.product.productlisting.dto.ListingResponseDto;
import com.enunas.backend.product.productlisting.dto.UpdateListingDto;
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
@RequiredArgsConstructor
public class ProductListingController {

    private final ProductListingService productListingService;

    @PostMapping("/products/{productId}/listings")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ListingResponseDto> createListing(
            @PathVariable Long productId,
            @Valid @RequestBody CreateListingDto dto,
            @AuthenticationPrincipal User creator) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productListingService.createListing(productId, dto, creator));
    }

    @GetMapping("/products/{productId}/listings")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<List<ListingResponseDto>> getListingsByProduct(@PathVariable Long productId) {
        return ResponseEntity.ok(productListingService.getActiveListingsByProduct(productId));
    }

    @GetMapping("/listings/{listingId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<ListingResponseDto> getListing(@PathVariable Long listingId) {
        return ResponseEntity.ok(productListingService.getListingById(listingId));
    }

    @GetMapping("/listings")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<List<ListingResponseDto>> getListingsByRegion(
            @RequestParam(required = false) String region) {
        return ResponseEntity.ok(productListingService.getActiveListingsByRegion(region));
    }

    @PutMapping("/products/{productId}/listings/{listingId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ListingResponseDto> updateListing(
            @PathVariable Long productId,
            @PathVariable Long listingId,
            @Valid @RequestBody UpdateListingDto dto,
            @AuthenticationPrincipal User creator) {
        return ResponseEntity.ok(productListingService.updateListing(productId, listingId, dto, creator));
    }

    @DeleteMapping("/products/{productId}/listings/{listingId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<Void> deleteListing(
            @PathVariable Long productId,
            @PathVariable Long listingId,
            @AuthenticationPrincipal User creator) {
        productListingService.deleteListing(productId, listingId, creator);
        return ResponseEntity.noContent().build();
    }
}
