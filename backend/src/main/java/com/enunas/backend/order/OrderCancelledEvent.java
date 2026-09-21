package com.enunas.backend.order;

import java.math.BigDecimal;

/**
 * Published after an admin's order-cancellation transaction commits.
 *
 * @param refundAmount the amount refunded to the customer, or null when the order was never paid
 */
public record OrderCancelledEvent(String buyerEmail, String orderNumber, CancelReason reason, String note,
                                  BigDecimal refundAmount, String currency) {}
