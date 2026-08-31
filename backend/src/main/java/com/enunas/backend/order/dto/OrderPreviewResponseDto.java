package com.enunas.backend.order.dto;

import com.enunas.backend.order.OrderPricingDraft;
import com.enunas.backend.shipping.ShippingCalculationMethod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checkout summary shown before payment — products, shipping, and total broken out separately,
 * computed by the exact same {@link OrderPricingDraft} pipeline {@code createOrder} charges from.
 * A supplied discount code IS reflected here: {@code discountCode}/{@code discountAmount}/
 * {@code total} show the real discount-adjusted figures checkout will charge. Previewing still
 * never reserves the code's usage — the preview path runs
 * {@code DiscountService#validateAndCompute} (side-effect free) while order creation runs
 * {@code validateAndApply} (which reserves). An invalid/expired/exhausted code makes the preview
 * fail the same way placing the order would, rather than silently quoting an undiscounted total.
 */
@Getter
@Builder
public class OrderPreviewResponseDto {

    private List<PreviewItem> items;
    private BigDecimal subtotal;
    private String discountCode;
    private BigDecimal discountAmount;
    private List<PreviewShippingLine> shippingBreakdown;
    private BigDecimal shippingTotal;
    private BigDecimal total;
    private String currency;

    @Getter
    @Builder
    public static class PreviewItem {
        private Long listingId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }

    @Getter
    @Builder
    public static class PreviewShippingLine {
        private Long brandId;
        private String brandName;
        private BigDecimal amount;
        private String currency;
        private ShippingCalculationMethod calculationMethod;
    }

    public static OrderPreviewResponseDto from(OrderPricingDraft draft) {
        List<PreviewItem> items = draft.orderItems().stream()
                .map(i -> PreviewItem.builder()
                        // NOT i.getVariant().getId() — see OrderItem.listingIdSnapshot javadoc.
                        .listingId(i.getListingIdSnapshot())
                        .productName(i.getProductSnapshotName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getPriceAtPurchase())
                        .lineTotal(i.getLineGross())
                        .build())
                .toList();

        List<PreviewShippingLine> shippingLines = draft.shippingLines().stream()
                .map(l -> PreviewShippingLine.builder()
                        .brandId(l.brandId())
                        .brandName(l.brandName())
                        .amount(l.result().amount())
                        .currency(l.result().currency())
                        .calculationMethod(l.result().method())
                        .build())
                .toList();

        return OrderPreviewResponseDto.builder()
                .items(items)
                .subtotal(draft.subtotal())
                .discountCode(draft.discount() != null ? draft.discount().code().getCode() : null)
                .discountAmount(draft.discountAmount())
                .shippingBreakdown(shippingLines)
                .shippingTotal(draft.shippingTotal())
                .total(draft.total())
                .currency(draft.currency())
                .build();
    }
}
