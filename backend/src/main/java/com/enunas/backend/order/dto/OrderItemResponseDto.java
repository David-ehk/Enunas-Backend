package com.enunas.backend.order.dto;

import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.order.CancelReason;
import com.enunas.backend.order.OrderItem;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class OrderItemResponseDto {

    private Long id;
    private Long listingId;
    private String productName;
    private String variantSku;
    private String variantColor;
    private String variantSize;
    private String imageUrl;
    private BigDecimal priceAtPurchase;
    private BigDecimal discountPriceAtPurchase;
    private int quantity;
    private BigDecimal lineTotal;
    private LocalDateTime cancelledAt;
    private CancelReason cancellationReason;
    /** ACTIVE, PENDING (claimed — refund not recorded yet) or CANCELLED. */
    private String cancellationState;
    /** Null in brand-scoped views. */
    private String refundTransactionId;
    /** Null in brand-scoped views. */
    private String cancellationClaimKey;

    /** Brand-safe view: cancellation state without refund or claim references. */
    public static OrderItemResponseDto from(OrderItem item, MediaUrlResolver resolver) {
        return base(item, resolver).build();
    }

    /** Customer and admin views — adds the refund and claim references brands must never see. */
    public static OrderItemResponseDto withRefundDetails(OrderItem item, MediaUrlResolver resolver) {
        return base(item, resolver)
                .refundTransactionId(item.getRefundTransactionId())
                .cancellationClaimKey(item.getCancellationClaimKey())
                .build();
    }

    private static OrderItemResponseDtoBuilder base(OrderItem item, MediaUrlResolver resolver) {
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
                .imageUrl(resolver.resolve(item.getVariantSnapshotImageKey()))
                .priceAtPurchase(item.getPriceAtPurchase())
                .discountPriceAtPurchase(item.getDiscountPriceAtPurchase())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .cancelledAt(item.getCancelledAt())
                .cancellationReason(item.getCancellationReason())
                .cancellationState(item.isCancellationSettled() ? "CANCELLED"
                        : item.isCancelled() ? "PENDING" : "ACTIVE");
    }
}
