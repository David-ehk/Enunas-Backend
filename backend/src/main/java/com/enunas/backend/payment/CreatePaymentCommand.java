package com.enunas.backend.payment;

import java.math.BigDecimal;

public record CreatePaymentCommand(
        BigDecimal amount,
        String currency,
        String description,
        String redirectUrl
) {}
