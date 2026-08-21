package com.enunas.backend.order;

/** Published after an admin's order-cancellation transaction commits. */
public record OrderCancelledEvent(String buyerEmail, String orderNumber, CancelReason reason, String note) {
}
