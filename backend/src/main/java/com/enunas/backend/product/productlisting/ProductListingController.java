package com.enunas.backend.product.productlisting;

import com.enunas.backend.product.productlisting.dto.CreateListingDto;
import com.enunas.backend.product.productlisting.dto.ListingResponseDto;
import com.enunas.backend.product.productlisting.dto.UpdateListingDto;
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

    // Public storefront reads (no auth — see SecurityConfiguration GET /products|listings/**)

    // `viewer` is null for anonymous storefront traffic; when present it exempts the owning brand
    // and admins from the storefront gate, exactly as ProductController does for products.

    @GetMapping("/products/{productId}/listings")
    public ResponseEntity<List<ListingResponseDto>> getListingsByProduct(
            @PathVariable Long productId,
            @AuthenticationPrincipal User viewer) {
        return ResponseEntity.ok(productListingService.getActiveListingsByProduct(productId, viewer));
    }

    @GetMapping("/listings/{listingId}")
    public ResponseEntity<ListingResponseDto> getListing(
            @PathVariable Long listingId,
            @AuthenticationPrincipal User viewer) {
        return ResponseEntity.ok(productListingService.getListingById(listingId, viewer));
    }

    /** Paged: with no region this spans the whole catalogue. */
    @GetMapping("/listings")
    public ResponseEntity<Page<ListingResponseDto>> getListingsByRegion(
            @RequestParam(required = false) String region,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productListingService.getActiveListingsByRegion(region, pageable));
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
