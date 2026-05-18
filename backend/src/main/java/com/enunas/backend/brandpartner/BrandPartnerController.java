package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.brandpartner.dto.RegisterBrandPartnerDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import com.enunas.backend.user.User;
import com.enunas.backend.user.dto.VerifyUserDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/brandpartner")
@RequiredArgsConstructor
public class BrandPartnerController {

    private final BrandPartnerService brandPartnerService;

    /** Public brand-partner application: creates User + BrandPartner together. */
    @PostMapping("/apply")
    public ResponseEntity<BrandPartnerResponseDto> applyForBrand(
            @RequestBody @Valid RegisterBrandPartnerDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(brandPartnerService.applyForBrand(dto));
    }

    /** Public: verify the brand applicant's email with the 6-digit code. */
    @PostMapping("/verify")
    public ResponseEntity<String> verify(@Valid @RequestBody VerifyUserDto dto) {
        brandPartnerService.verifyBrandApplicant(dto);
        return ResponseEntity.ok("Email verified. Awaiting admin approval.");
    }

    /** Public: re-issue the verification code if it expired. */
    @PostMapping("/resend-verification")
    public ResponseEntity<String> resendVerification(@RequestParam String email) {
        brandPartnerService.resendVerificationCode(email);
        return ResponseEntity.ok("Verification code resent");
    }

    /** Get own brand profile. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<BrandPartnerResponseDto> getMyProfile(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(brandPartnerService.getMyProfile(user));
    }

    /** Update own brand profile fields (description, logo, website, instagram). */
    @PatchMapping("/me")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<BrandPartnerResponseDto> updateMyProfile(
            @RequestBody @Valid UpdateBrandPartnerDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(brandPartnerService.updateMyProfile(dto, user));
    }

    /** Look up any brand by ID — accessible to any authenticated user. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BrandPartnerResponseDto> getBrandById(@PathVariable Long id) {
        return ResponseEntity.ok(brandPartnerService.getBrandById(id));
    }
}
