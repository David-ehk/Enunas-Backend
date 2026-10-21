package com.enunas.backend.admin;

import com.enunas.backend.order.ReturnRefundService;
import com.enunas.backend.order.dto.OrderRefundPreviewDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReconcileReturnRefundDto;
import com.enunas.backend.order.dto.ReleaseRefundDto;
import com.enunas.backend.order.dto.RefundOverviewRowDto;
import com.enunas.backend.order.dto.RefundPreviewDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRefundController {

    private final ReturnRefundService returnRefundService;

    @PostMapping("/returns/{returnNumber}/release-refund")
    public ResponseEntity<OrderResponseDto> releaseRefund(
            @PathVariable String returnNumber,
            @Valid @RequestBody(required = false) ReleaseRefundDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(returnRefundService.release(returnNumber,
                dto == null ? null : dto.overrideReason(), admin));
    }

    @PostMapping("/returns/{returnNumber}/reconcile-refund")
    public ResponseEntity<OrderResponseDto> reconcileRefund(
            @PathVariable String returnNumber,
            @Valid @RequestBody ReconcileReturnRefundDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(returnRefundService.reconcile(returnNumber, dto, admin));
    }

    @GetMapping("/returns/{returnNumber}/refund-preview")
    public ResponseEntity<RefundPreviewDto> preview(@PathVariable String returnNumber) {
        return ResponseEntity.ok(returnRefundService.preview(returnNumber));
    }

    @GetMapping("/orders/{orderId}/refund-preview")
    public ResponseEntity<OrderRefundPreviewDto> previewOrder(@PathVariable Long orderId) {
        return ResponseEntity.ok(returnRefundService.previewOrder(orderId));
    }

    @GetMapping("/refunds")
    public ResponseEntity<Page<RefundOverviewRowDto>> refunds(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(returnRefundService.overview(state, PageRequest.of(page, Math.min(size, 100))));
    }
}
