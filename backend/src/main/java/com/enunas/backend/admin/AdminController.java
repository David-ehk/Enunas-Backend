package com.enunas.backend.admin;

import com.enunas.backend.brandpartner.BrandPartnerService;
import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.order.OrderService;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.OrderResponseDto;
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

import java.math.BigDecimal;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final OrderService orderService;
    private final BrandPartnerService brandPartnerService;

    // ===== Brand partner management =====

    /**
     * Approve a brand application → flips BrandPartner.status=ACTIVE,
     * BrandPartner.approved=true, and User.adminApproved=true in one transaction.
     */
    @PostMapping("/brands/{brandId}/approve")
    public ResponseEntity<BrandPartnerResponseDto> approveBrand(@PathVariable Long brandId) {
        return ResponseEntity.ok(brandPartnerService.approveBrand(brandId));
    }

    /** Suspend a brand profile → status SUSPENDED. */
    @PostMapping("/brands/{brandId}/suspend")
    public ResponseEntity<BrandPartnerResponseDto> suspendBrand(@PathVariable Long brandId) {
        return ResponseEntity.ok(brandPartnerService.suspendBrand(brandId));
    }

    // ===== Order: forward flow =====

    @GetMapping("/orders")
    public ResponseEntity<Page<OrderResponseDto>> getAllOrders(
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(orderService.getAllOrders(pageable));
    }

    @GetMapping("/orders/status/{status}")
    public ResponseEntity<Page<OrderResponseDto>> getOrdersByStatus(
            @PathVariable OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(orderService.getOrdersByStatus(status, pageable));
    }

    /** Advance order: PENDING→PAID, PAID→SHIPPED, SHIPPED→DELIVERED. */
    @PatchMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderResponseDto> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, status));
    }

    /** Cancel a PENDING order with a documented reason. */
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<OrderResponseDto> cancelOrder(
            @PathVariable Long orderId,
            @Valid @RequestBody CancelOrderDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId, dto, admin));
    }

    // ===== Order: return flow =====

    /** Approve a customer return request → RETURN_APPROVED. */
    @PostMapping("/orders/{orderId}/return/approve")
    public ResponseEntity<OrderResponseDto> approveReturn(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.approveReturn(orderId));
    }

    /** Mark returned goods as physically received → RETURN_RECEIVED + stock restored. */
    @PostMapping("/orders/{orderId}/return/receive")
    public ResponseEntity<OrderResponseDto> receiveReturn(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.receiveReturn(orderId));
    }

    /** Issue refund after goods are back in warehouse → REFUNDED. */
    @PostMapping("/orders/{orderId}/return/refund")
    public ResponseEntity<OrderResponseDto> processRefund(
            @PathVariable Long orderId,
            @RequestParam BigDecimal refundAmount) {
        return ResponseEntity.ok(orderService.processRefund(orderId, refundAmount));
    }
}
