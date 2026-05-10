package com.enunas.backend.payment;

public record PaymentResult(
        String paymentId,
        String checkoutUrl
) {}
