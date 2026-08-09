package com.enunas.backend.order;

import com.enunas.backend.discount.DiscountApplication;
import com.enunas.backend.order.dto.ShippingAddressDto;
import com.enunas.backend.shipping.ShippingCostResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * In-memory result of pricing a cart — shared by {@link OrderService#createOrder} (which
 * persists it) and {@link OrderService#previewOrder} (which doesn't). Keeping both on this single
 * computation path guarantees the checkout preview and the actual Mollie charge can never drift.
 */
public record OrderPricingDraft(
        List<OrderItem> orderItems,
        BigDecimal subtotal,
        DiscountApplication discount,
        BigDecimal discountAmount,
        List<ShippingLine> shippingLines,
        BigDecimal shippingTotal,
        BigDecimal total,
        ShippingAddressDto resolvedAddress,
        String currency
) {
    public record ShippingLine(Long brandId, String brandName, ShippingCostResult result) {}
}
