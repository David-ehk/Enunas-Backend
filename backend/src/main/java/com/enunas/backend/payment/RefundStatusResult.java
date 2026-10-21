package com.enunas.backend.payment;

import java.math.BigDecimal;

public record RefundStatusResult(Status status, BigDecimal amount, String paymentId) {

    public enum Status { QUEUED, PENDING, PROCESSING, REFUNDED, FAILED, CANCELED }

    public boolean isFinalSuccess() { return status == Status.REFUNDED; }

    public boolean isFinalFailure() { return status == Status.FAILED || status == Status.CANCELED; }
}
