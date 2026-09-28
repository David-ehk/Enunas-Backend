package com.enunas.backend.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * Published by OrderItemCancellationService.finalizeClaim — only once the refund is recorded, so a
 * released claim never tells anyone its items were cancelled. Handled AFTER_COMMIT.
 */
public record OrderItemsCancelledEvent(
        String buyerEmail,
        String orderNumber,
        String brandName,
        String brandEmail,
        List<String> itemDescriptions,
        BigDecimal refundAmount,
        String currency
) {}
