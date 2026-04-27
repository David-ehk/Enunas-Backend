package com.enunas.backend.order;

import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReturnRequestDto;
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

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponseDto> createOrder(
            @Valid @RequestBody CreateOrderDto dto,
            @AuthenticationPrincipal User buyer) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(dto, buyer));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public Page<OrderResponseDto> getMyOrders(
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable,
            @AuthenticationPrincipal User buyer) {
        return orderService.getMyOrders(buyer, pageable);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponseDto> getOrderById(
            @PathVariable Long orderId,
            @AuthenticationPrincipal User buyer) {
        return ResponseEntity.ok(orderService.getMyOrderById(orderId, buyer));
    }

    @PostMapping("/{orderId}/return")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponseDto> requestReturn(
            @PathVariable Long orderId,
            @Valid @RequestBody ReturnRequestDto dto,
            @AuthenticationPrincipal User buyer) {
        return ResponseEntity.ok(orderService.requestReturn(orderId, dto, buyer));
    }
}
