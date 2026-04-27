package com.enunas.backend.order;

import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/brand/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('BRAND_PARTNER')")
public class BrandPartnerOrderController {

    private final OrderService orderService;

    /** All orders that contain at least one of this brand partner's products. */
    @GetMapping
    public Page<OrderResponseDto> getMyBrandOrders(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable,
            @AuthenticationPrincipal User brandPartner) {
        return orderService.getMyBrandOrders(brandPartner, pageable);
    }

    /** Confirm that the order has been handed to the carrier → SHIPPED. */
    @PostMapping("/{orderId}/ship")
    public ResponseEntity<OrderResponseDto> confirmShipment(
            @PathVariable Long orderId,
            @Valid @RequestBody ShipmentConfirmationDto dto,
            @AuthenticationPrincipal User brandPartner) {
        return ResponseEntity.ok(orderService.confirmShipment(orderId, dto, brandPartner));
    }

    /** Report a shipping problem (e.g. damaged goods, wrong item) → SHIPPING_PROBLEM. */
    @PostMapping("/{orderId}/problem")
    public ResponseEntity<OrderResponseDto> reportShippingProblem(
            @PathVariable Long orderId,
            @Valid @RequestBody ShippingProblemDto dto,
            @AuthenticationPrincipal User brandPartner) {
        return ResponseEntity.ok(orderService.reportShippingProblem(orderId, dto, brandPartner));
    }
}
