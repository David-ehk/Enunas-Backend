package com.enunas.backend.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * Published inside {@code OrderService.confirmPaymentByWebhook}'s transaction, right after the
 * order is marked PAID; handled AFTER_COMMIT so a mail failure can never roll back an
 * already-captured payment — same pattern as {@link RefundCompletedEvent} /
 * {@link ReturnApprovedEvent}. Carries a pre-formatted snapshot (not the {@link Order} entity)
 * because the listener runs after the transaction — and the session — that loaded it are closed.
 */
public record OrderConfirmationEvent(
        String buyerEmail,
        String orderNumber,
        List<String> itemLines,
        BigDecimal subtotal,
        BigDecimal shippingTotal,
        List<String> shippingBreakdown,
        BigDecimal discountAmount,
        BigDecimal total,
        String currency,
        String shippingAddressBlock,
        String orderLink
) {}
