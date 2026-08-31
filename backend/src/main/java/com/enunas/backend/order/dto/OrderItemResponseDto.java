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
    private int quantity;
    private BigDecimal lineTotal;

    public static OrderItemResponseDto from(OrderItem item) {
        return OrderItemResponseDto.builder()
                .id(item.getId())
                // NOT item.getVariant().getId() — that's the variant id, a different identifier
                // the client never sent. listingIdSnapshot is null only for orders placed before
                // this field existed (V28); see OrderItem.listingIdSnapshot javadoc.
                .listingId(item.getListingIdSnapshot())
                .productName(item.getProductSnapshotName())
                .variantSku(item.getVariantSnapshotSku())
                .variantColor(item.getVariantSnapshotColor())
                .variantSize(item.getVariantSnapshotSize())
                .priceAtPurchase(item.getPriceAtPurchase())
                .discountPriceAtPurchase(item.getDiscountPriceAtPurchase())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .build();
    }
}
