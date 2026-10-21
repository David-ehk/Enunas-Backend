package com.enunas.backend.payment;

import java.math.BigDecimal;
import java.util.Map;

public record RefundCommand(
        String paymentId,
        BigDecimal amount,
        String reason,
        String idempotencyKey,
        Map<String, Object> metadata
) {
    public RefundCommand {
        metadata = metadata == null ? Map.of() : metadata;
    }

    public RefundCommand(String paymentId, BigDecimal amount, String reason, String idempotencyKey) {
        this(paymentId, amount, reason, idempotencyKey, Map.of());
    }
}
