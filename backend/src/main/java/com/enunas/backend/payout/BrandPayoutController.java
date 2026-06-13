package com.enunas.backend.payout;

import com.enunas.backend.brandpartner.BrandPartnerService;
import com.enunas.backend.payout.dto.PayoutResponseDto;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Brand-partner view onto their own payouts (read-only). The payout lifecycle
 * (generate → approve → paid) is driven exclusively by the admin; the brand
 * only observes status, amount and the bank-transfer reference once paid.
 */
@RestController
@RequestMapping("/brand/payouts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('BRAND_PARTNER')")
public class BrandPayoutController {

    private final PayoutRepository payoutRepository;
    private final BrandPartnerService brandPartnerService;

    /** All payouts of the calling brand partner, newest first. */
    @GetMapping
    public ResponseEntity<List<PayoutResponseDto>> getMyPayouts(@AuthenticationPrincipal User user) {
        Long brandId = brandPartnerService.findByUser(user).getId();
        return ResponseEntity.ok(
                payoutRepository.findByBrandPartnerIdOrderByCreatedAtDesc(brandId).stream()
                        .map(PayoutResponseDto::from)
                        .toList());
    }
}
