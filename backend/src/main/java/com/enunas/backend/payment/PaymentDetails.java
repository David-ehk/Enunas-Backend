package com.enunas.backend.payment;

import java.math.BigDecimal;

public record PaymentDetails(
        boolean isPaid,
        BigDecimal amount
) {}
