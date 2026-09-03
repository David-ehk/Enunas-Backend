package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.dto.BrandPublicProfileDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated brand reads — the storefront brand page. Deliberately a separate
 * controller/prefix from {@link BrandPartnerController} (`/brandpartner/**`), which
 * {@code SecurityConfiguration} locks to {@code hasAnyRole("BRAND_PARTNER", "ADMIN")} as a whole
 * prefix; nesting a public route under it would need a carve-out ordered above that rule. A
 * distinct {@code /brands/**} prefix with its own {@code permitAll()} avoids that entirely.
 */
@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandPartnerService brandPartnerService;

    /** Public brand profile for the storefront — only exposes fields safe for a logged-out visitor. */
    @GetMapping("/{id}/public-profile")
    public ResponseEntity<BrandPublicProfileDto> getPublicProfile(@PathVariable Long id) {
        return ResponseEntity.ok(brandPartnerService.getPublicProfile(id));
    }
}
