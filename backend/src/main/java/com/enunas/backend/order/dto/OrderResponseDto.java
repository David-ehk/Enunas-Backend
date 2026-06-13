package com.enunas.backend.order.dto;

import com.enunas.backend.discount.DiscountType;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.order.ReturnOrder;
import com.enunas.backend.order.ShippingAddress;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder(toBuilder = true)
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
    private String discountCode;
    private DiscountType discountType;
    private BigDecimal discountPercent;
    private BigDecimal discountAmount;
    private String notes;
    private String checkoutUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Return fields — populated only when a ReturnOrder exists for this order.
    private String returnNumber;
    private String returnReason;
    private String returnDescription;
    private LocalDateTime returnRequestedAt;
    // Pre-built return ship-to address (brand's business address). Present when a return exists
    // so customers can find it in-app without depending on email delivery.
    private String returnShipToAddress;

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
                .discountCode(order.getDiscountCode())
                .discountType(order.getDiscountType())
                .discountPercent(order.getDiscountPercent())
                .discountAmount(order.getDiscountAmount())
                .notes(order.getNotes())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    public static OrderResponseDto from(Order order, String checkoutUrl) {
        return from(order).toBuilder().checkoutUrl(checkoutUrl).build();
    }

    public static OrderResponseDto withReturn(Order order, ReturnOrder ret, String returnShipToAddress) {
        return from(order).toBuilder()
                .returnNumber(ret.getReturnNumber())
                .returnReason(ret.getReason() != null ? ret.getReason().name() : null)
                .returnDescription(ret.getDescription())
                .returnRequestedAt(ret.getRequestedAt())
                .returnShipToAddress(returnShipToAddress)
                .build();
    }
}
