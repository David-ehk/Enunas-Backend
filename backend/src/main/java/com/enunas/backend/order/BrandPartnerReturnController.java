package com.enunas.backend.order;

import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.UploadReturnLabelDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Brand-facing actions on returns. A brand already sees its returns embedded in
 * {@code GET /brand/orders} (via {@code OrderResponseDto.returns}); this is where it acts on one.
 */
@RestController
@RequestMapping("/brand/returns")
@RequiredArgsConstructor
@PreAuthorize("hasRole('BRAND_PARTNER')")
public class BrandPartnerReturnController {

    private final OrderService orderService;

    /** Upload a label the brand generated itself (its own carrier account) — the MVP path. */
    @PostMapping("/{returnNumber}/label")
    public ResponseEntity<OrderResponseDto> uploadLabel(
            @PathVariable String returnNumber,
            @Valid @RequestBody UploadReturnLabelDto dto,
            @AuthenticationPrincipal User brandPartner) {
        return ResponseEntity.ok(orderService.uploadReturnLabel(returnNumber, dto, brandPartner));
    }
}
