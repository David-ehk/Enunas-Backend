package com.enunas.backend.order;

import java.math.BigDecimal;

/**
 * Published inside {@code RefundPersistenceHelper.persist}'s transaction; handled AFTER_COMMIT so a
 * mail failure can never roll back an already-committed refund — same pattern as
 * {@link ReturnApprovedEvent}. One event per BRAND refund, mirroring the return-approval email.
 */
public record RefundCompletedEvent(
        String buyerEmail,
        String orderNumber,
        String returnNumber,
        String brandName,
        BigDecimal refundAmount,
        String currency
) {}
