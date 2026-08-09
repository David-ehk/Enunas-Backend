package com.enunas.backend.admin;

import com.enunas.backend.admin.dto.AdminProductResponseDto;
import com.enunas.backend.admin.dto.SetPayoutProfileDto;
import com.enunas.backend.admin.dto.SetShippingProfileDto;
import com.enunas.backend.admin.dto.RejectionDto;
import com.enunas.backend.customer.dto.CustomerBrandSpendingDto;
import com.enunas.backend.ledger.ReconciliationService;
import com.enunas.backend.payout.PayoutStatus;
import com.enunas.backend.payout.dto.MarkAsPaidDto;
import com.enunas.backend.payout.dto.PayoutDashboardDto;
import com.enunas.backend.payout.dto.PayoutResponseDto;

import java.util.List;
import com.enunas.backend.brandpartner.BrandPartnerService;
import com.enunas.backend.brandpartner.dto.AdminBrandMasterDataDto;
import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.customer.dto.CustomerResponseDto;
import com.enunas.backend.customer.dto.UpdateCustomerProfileDto;
import com.enunas.backend.order.OrderService;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReturnRequestDto;
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
    private final BrandPartnerService brandPartnerService;

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

    @PatchMapping("/brands/{brandId}/payout-profile")
    public ResponseEntity<BrandPartnerResponseDto> setBrandPayoutProfile(
            @PathVariable Long brandId,
            @Valid @RequestBody SetPayoutProfileDto dto) {
        return ResponseEntity.ok(adminService.setBrandPayoutProfile(brandId, dto.getIban(), dto.getBankAccountHolder()));
    }

    @PatchMapping("/brands/{brandId}/shipping-profile")
    public ResponseEntity<BrandPartnerResponseDto> setBrandShippingProfile(
            @PathVariable Long brandId,
            @Valid @RequestBody SetShippingProfileDto dto) {
        return ResponseEntity.ok(adminService.setBrandShippingProfile(brandId, dto));
    }

    /** Admin edit of a brand's §22f master data (legal name + address + tax IDs only). */
    @PatchMapping("/brands/{brandId}")
    public ResponseEntity<BrandPartnerResponseDto> updateBrandMasterData(
            @PathVariable Long brandId,
            @Valid @RequestBody AdminBrandMasterDataDto dto) {
        return ResponseEntity.ok(brandPartnerService.updateBrandMasterData(brandId, dto));
    }

    // ===== Payouts =====

    @PostMapping("/payouts/generate")
    public ResponseEntity<List<PayoutResponseDto>> generatePayouts() {
        return ResponseEntity.ok(adminService.generatePayouts());
    }

    @GetMapping("/payouts/dashboard")
    public ResponseEntity<PayoutDashboardDto> getPayoutDashboard() {
        return ResponseEntity.ok(adminService.getPayoutDashboard());
    }

    @GetMapping("/payouts")
    public ResponseEntity<Page<PayoutResponseDto>> listPayouts(
            @RequestParam(required = false) PayoutStatus status,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(adminService.listPayouts(status, pageable));
    }

    @GetMapping("/payouts/{payoutId}")
    public ResponseEntity<PayoutResponseDto> getPayoutById(@PathVariable Long payoutId) {
        return ResponseEntity.ok(adminService.getPayoutById(payoutId));
    }

    @PostMapping("/payouts/{payoutId}/approve")
    public ResponseEntity<PayoutResponseDto> approvePayout(
            @PathVariable Long payoutId,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(adminService.approvePayout(payoutId, admin));
    }

    @PostMapping("/payouts/{payoutId}/paid")
    public ResponseEntity<PayoutResponseDto> markPayoutAsPaid(
            @PathVariable Long payoutId,
            @Valid @RequestBody MarkAsPaidDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(adminService.markPayoutAsPaid(payoutId, dto, admin));
    }

    @PostMapping("/payouts/{payoutId}/cancel")
    public ResponseEntity<PayoutResponseDto> cancelPayout(@PathVariable Long payoutId) {
        return ResponseEntity.ok(adminService.cancelPayout(payoutId));
    }

    // ===== Reconciliation =====

    @GetMapping("/reconciliation")
    public ResponseEntity<List<ReconciliationService.DriftReport>> checkReconciliation() {
        return ResponseEntity.ok(adminService.checkReconciliation());
    }

    @GetMapping("/reconciliation/{brandId}")
    public ResponseEntity<ReconciliationService.DriftReport> checkBrandReconciliation(@PathVariable Long brandId) {
        return ResponseEntity.ok(adminService.checkBrandReconciliation(brandId));
    }

    @PostMapping("/reconciliation/{brandId}/rebuild")
    public ResponseEntity<ReconciliationService.DriftReport> rebuildBrandEconomics(@PathVariable Long brandId) {
        return ResponseEntity.ok(adminService.rebuildBrandEconomics(brandId));
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

    @PostMapping("/products/{productId}/hide")
    public ResponseEntity<AdminProductResponseDto> hideProduct(
            @PathVariable Long productId,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(adminService.hideProduct(productId, admin));
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

    @GetMapping("/customers/{id}/brand-spending")
    public ResponseEntity<List<CustomerBrandSpendingDto>> getCustomerBrandSpending(@PathVariable Long id) {
        return ResponseEntity.ok(customerService.getCustomerBrandSpending(id));
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

    // ===== Returns — addressed per brand. An order spanning several brands has one return per
    // brand, each with its own destination, stock restore and refund. =====

    /**
     * Goodwill return, initiated by an admin rather than the customer — bypasses the 14-day
     * Widerruf window that {@code POST /orders/{orderId}/return} enforces. Same split/merge
     * behaviour otherwise.
     */
    @PostMapping("/orders/{orderId}/return")
    public ResponseEntity<OrderResponseDto> adminRequestReturn(
            @PathVariable Long orderId,
            @Valid @RequestBody ReturnRequestDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(orderService.adminRequestReturn(orderId, dto, admin));
    }

    @PostMapping("/returns/{returnNumber}/approve")
    public ResponseEntity<OrderResponseDto> approveReturn(@PathVariable String returnNumber) {
        return ResponseEntity.ok(orderService.approveReturn(returnNumber));
    }

    @PostMapping("/returns/{returnNumber}/receive")
    public ResponseEntity<OrderResponseDto> receiveReturn(@PathVariable String returnNumber) {
        return ResponseEntity.ok(orderService.receiveReturn(returnNumber));
    }

    /** {@code refundAmount} is optional — omit it to refund this brand's full returned value. */
    @PostMapping("/returns/{returnNumber}/refund")
    public ResponseEntity<OrderResponseDto> processRefund(
            @PathVariable String returnNumber,
            @RequestParam(required = false) BigDecimal refundAmount) {
        return ResponseEntity.ok(orderService.processRefund(returnNumber, refundAmount));
    }

    // ===== Deprecated order-scoped aliases. Kept so existing callers keep working while they move
    // to /admin/returns/{returnNumber}. They resolve only when the order has exactly ONE return;
    // on a multi-brand order they return 409 rather than guess which brand the admin meant. =====

    @Deprecated
    @PostMapping("/orders/{orderId}/return/approve")
    public ResponseEntity<OrderResponseDto> approveReturnByOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.approveReturnByOrder(orderId));
    }

    @Deprecated
    @PostMapping("/orders/{orderId}/return/receive")
    public ResponseEntity<OrderResponseDto> receiveReturnByOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.receiveReturnByOrder(orderId));
    }

    @Deprecated
    @PostMapping("/orders/{orderId}/return/refund")
    public ResponseEntity<OrderResponseDto> processRefundByOrder(
            @PathVariable Long orderId,
            @RequestParam(required = false) BigDecimal refundAmount) {
        return ResponseEntity.ok(orderService.processRefundByOrder(orderId, refundAmount));
    }
}
