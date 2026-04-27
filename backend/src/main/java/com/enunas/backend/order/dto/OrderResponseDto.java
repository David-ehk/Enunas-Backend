package com.enunas.backend.order.dto;

import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.order.ShippingAddress;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class OrderResponseDto {

    private Long id;
    private String orderNumber;
    private Long buyerId;
    private String buyerEmail;
    private OrderStatus status;
    private ShippingAddress shippingAddress;
    private List<OrderItemResponseDto> items;
    private BigDecimal subtotal;
    private BigDecimal shippingTotal;
    private BigDecimal total;
    private String currency;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OrderResponseDto from(Order order) {
        return OrderResponseDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .buyerId(order.getBuyer().getId())
                .buyerEmail(order.getBuyer().getEmail())
                .status(order.getStatus())
                .shippingAddress(order.getShippingAddress())
                .items(order.getItems().stream()
                        .map(OrderItemResponseDto::from)
                        .toList())
                .subtotal(order.getSubtotal())
                .shippingTotal(order.getShippingTotal())
                .total(order.getTotal())
                .currency(order.getCurrency())
                .notes(order.getNotes())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
