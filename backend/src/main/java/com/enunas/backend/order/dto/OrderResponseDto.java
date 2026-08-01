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

    /**
     * Every return on this order — one per brand. Present as soon as a return is requested, so the
     * customer never has to wait for admin approval (or an email) to learn where to ship goods.
     */
    private List<ReturnSummaryDto> returns;

    // Legacy single-return fields. Populated ONLY when the order has exactly one return, so
    // existing single-brand clients keep working; null on a multi-brand return, where no single
    // value can be correct. Read `returns` instead.
    @Deprecated private String returnNumber;
    @Deprecated private String returnReason;
    @Deprecated private String returnDescription;
    @Deprecated private LocalDateTime returnRequestedAt;
    @Deprecated private String returnShipToAddress;

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

    public static OrderResponseDto withReturns(Order order, List<ReturnOrder> returns) {
        var builder = from(order).toBuilder()
                .returns(returns.stream().map(ReturnSummaryDto::from).toList());

        // Back-compat: only fill the legacy scalars when there is exactly one return. On a
        // multi-brand order any single address would be wrong for at least one brand — leaving
        // them null forces callers onto `returns` rather than quietly misdirecting a parcel.
        if (returns.size() == 1) {
            ReturnOrder ret = returns.get(0);
            builder.returnNumber(ret.getReturnNumber())
                    .returnReason(ret.getReason() != null ? ret.getReason().name() : null)
                    .returnDescription(ret.getDescription())
                    .returnRequestedAt(ret.getRequestedAt())
                    .returnShipToAddress(ret.getShipToFormatted());
        }
        return builder.build();
    }
}
