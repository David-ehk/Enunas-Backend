package com.enunas.backend.payment;

import java.math.BigDecimal;

public record RefundCommand(
        String paymentId,
        BigDecimal amount,
        String reason
) {}
