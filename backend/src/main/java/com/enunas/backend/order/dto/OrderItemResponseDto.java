package com.enunas.backend.order.dto;

import com.enunas.backend.order.OrderItem;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class OrderItemResponseDto {

    private Long id;
    private Long listingId;
    private String productName;
    private String variantSku;
    private String variantColor;
    private String variantSize;
    private BigDecimal priceAtPurchase;
    private BigDecimal discountPriceAtPurchase;
    private BigDecimal shippingCostAtPurchase;
    private int quantity;
    private BigDecimal lineTotal;

    public static OrderItemResponseDto from(OrderItem item) {
        return OrderItemResponseDto.builder()
                .id(item.getId())
                .listingId(item.getProductListing() != null ? item.getProductListing().getId() : null)
                .productName(item.getProductName())
                .variantSku(item.getVariantSku())
                .variantColor(item.getVariantColor())
                .variantSize(item.getVariantSize())
                .priceAtPurchase(item.getPriceAtPurchase())
                .discountPriceAtPurchase(item.getDiscountPriceAtPurchase())
                .shippingCostAtPurchase(item.getShippingCostAtPurchase())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .build();
    }
}
