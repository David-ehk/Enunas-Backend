package com.enunas.backend.order;

import java.math.BigDecimal;

/**
 * Published by {@code OrderService.confirmPaymentByWebhook} when a payment is captured for an order
 * that will never be fulfilled: it was already CANCELLED — typically auto-expired after 30 minutes,
 * with the customer finishing payment at Mollie afterwards — or a line sold out before the payment
 * arrived. The customer has been charged but has no order; the full amount is refunded
 * automatically (or by an admin if that fails). Handled AFTER_COMMIT so a mail failure can never
 * roll back the recorded payment or the REFUND_REQUIRED flag.
 *
 * @param reason customer-facing German sentence saying why the order could not be fulfilled
 */
public record LatePaymentRefundRequiredEvent(
        String buyerEmail,
        String orderNumber,
        BigDecimal amount,
        String currency,
        String reason
) {}
