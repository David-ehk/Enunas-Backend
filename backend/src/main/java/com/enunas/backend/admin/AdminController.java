package com.enunas.backend.admin;

import com.enunas.backend.admin.dto.AdminProductResponseDto;
import com.enunas.backend.admin.dto.RejectionDto;
import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.customer.dto.CustomerResponseDto;
import com.enunas.backend.customer.dto.UpdateCustomerProfileDto;
import com.enunas.backend.order.OrderService;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.product.dto.UpdateProductDto;
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

/**
 * Single entry point for all admin actions. Admin is identified purely by ROLE_ADMIN —
 * there is no Admin entity. Brand and product moderation logic lives in {@link AdminService};
 * customer and order admin operations delegate to their feature services.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final CustomerService customerService;
    private final OrderService orderService;

    // ===== Brand-partner moderation =====

    @GetMapping("/brands")
    public ResponseEntity<Page<BrandPartnerResponseDto>> getAllBrands(
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(adminService.getAllBrands(pageable));
    }

    @PostMapping("/brands/{brandId}/approve")
    public ResponseEntity<BrandPartnerResponseDto> approveBrand(@PathVariable Long brandId) {
        return ResponseEntity.ok(adminService.approveBrand(brandId));
    }

    @PostMapping("/brands/{brandId}/reject")
    public ResponseEntity<BrandPartnerResponseDto> rejectBrand(@PathVariable Long brandId) {
        return ResponseEntity.ok(adminService.rejectBrand(brandId));
    }

    @PostMapping("/brands/{brandId}/suspend")
    public ResponseEntity<BrandPartnerResponseDto> suspendBrand(@PathVariable Long brandId) {
        return ResponseEntity.ok(adminService.suspendBrand(brandId));
    }

    // ===== Product moderation =====

    @GetMapping("/products")
    public ResponseEntity<Page<AdminProductResponseDto>> getAllProducts(
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(adminService.getAllProducts(pageable));
    }

    @PatchMapping("/products/{productId}")
    public ResponseEntity<AdminProductResponseDto> updateProduct(
            @PathVariable Long productId,
            @Valid @RequestBody UpdateProductDto dto) {
        return ResponseEntity.ok(adminService.updateProduct(productId, dto));
    }

    @DeleteMapping("/products/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long productId) {
        adminService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/{productId}/approve")
    public ResponseEntity<AdminProductResponseDto> approveProduct(
            @PathVariable Long productId,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(adminService.approveProduct(productId, admin));
    }

    @PostMapping("/products/{productId}/reject")
    public ResponseEntity<AdminProductResponseDto> rejectProduct(
            @PathVariable Long productId,
            @Valid @RequestBody(required = false) RejectionDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(adminService.rejectProduct(productId, dto, admin));
    }

    // ===== Customer management (delegates to CustomerService) =====

    @GetMapping("/customers")
    public ResponseEntity<Page<CustomerResponseDto>> getAllCustomers(
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(customerService.getAllCustomers(pageable));
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<CustomerResponseDto> getCustomerById(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getCustomerById(id));
    }

    @PatchMapping("/customers/{id}")
    public ResponseEntity<CustomerResponseDto> updateCustomer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCustomerProfileDto dto) {
        return ResponseEntity.ok(customerService.updateCustomerByAdmin(id, dto));
    }

    // ===== Order management (delegates to OrderService) =====

    @GetMapping("/orders")
    public ResponseEntity<Page<OrderResponseDto>> getAllOrders(
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(orderService.getAllOrders(pageable));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponseDto> getOrderById(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/orders/status/{status}")
    public ResponseEntity<Page<OrderResponseDto>> getOrdersByStatus(
            @PathVariable OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(orderService.getOrdersByStatus(status, pageable));
    }

    @PatchMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderResponseDto> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, status));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<OrderResponseDto> cancelOrder(
            @PathVariable Long orderId,
            @Valid @RequestBody CancelOrderDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId, dto, admin));
    }

    @PostMapping("/orders/{orderId}/return/approve")
    public ResponseEntity<OrderResponseDto> approveReturn(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.approveReturn(orderId));
    }

    @PostMapping("/orders/{orderId}/return/receive")
    public ResponseEntity<OrderResponseDto> receiveReturn(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.receiveReturn(orderId));
    }

    @PostMapping("/orders/{orderId}/return/refund")
    public ResponseEntity<OrderResponseDto> processRefund(
            @PathVariable Long orderId,
            @RequestParam BigDecimal refundAmount) {
        return ResponseEntity.ok(orderService.processRefund(orderId, refundAmount));
    }
}
